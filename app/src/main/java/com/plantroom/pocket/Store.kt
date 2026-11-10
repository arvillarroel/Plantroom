package com.plantroom.pocket

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.plantroom.pocket.ui.*
import com.plantroom.pocket.runtime.GemmaEmbedder
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.ZipInputStream

data class Source(val id:String,val path:String,val type:String,val building:String,val asset:String,val date:String,val title:String,val synthetic:Boolean,val indexed:Boolean=false,val error:String="") {
    fun ui(score:Float?=null, excerpt:String="", location:String="")=UiItem(id,title,when(type){"image"->UiKind.PHOTO;"audio"->UiKind.AUDIO;else->UiKind.REPORT},building,asset,date,path,excerpt,location,synthetic,score)
}
data class VectorChunk(val sourceId:String,val ordinal:Int,val page:Int,val startMs:Int,val text:String,val vector:FloatArray)
data class SearchHit(val source:Source,val chunk:VectorChunk,val score:Float)

class Store(private val context:Context):SQLiteOpenHelper(context,"plantroom.db",null,1) {
    val library=File(context.filesDir,"library").apply{mkdirs()}
    override fun onCreate(db:SQLiteDatabase){
        db.execSQL("CREATE TABLE sources(id TEXT PRIMARY KEY,path TEXT NOT NULL,type TEXT NOT NULL,building TEXT NOT NULL,asset TEXT,date TEXT,title TEXT,synthetic INTEGER,indexed INTEGER DEFAULT 0,error TEXT DEFAULT '')")
        db.execSQL("CREATE TABLE chunks(source TEXT NOT NULL,ordinal INTEGER NOT NULL,page INTEGER,start_ms INTEGER,text TEXT,vector BLOB,PRIMARY KEY(source,ordinal))")
    }
    override fun onUpgrade(db:SQLiteDatabase,old:Int,new:Int){}
    fun ensureIndexVersion(){
        val db=writableDatabase
        db.execSQL("CREATE TABLE IF NOT EXISTS index_meta(name TEXT PRIMARY KEY,value TEXT)")
        val old=db.rawQuery("SELECT value FROM index_meta WHERE name='version'",null).use{if(it.moveToFirst())it.getString(0)else null}
        if(old!=GemmaEmbedder.INDEX_VERSION){db.beginTransaction();try{db.delete("chunks",null,null);db.execSQL("UPDATE sources SET indexed=0,error=''");db.insertWithOnConflict("index_meta",null,ContentValues().apply{put("name","version");put("value",GemmaEmbedder.INDEX_VERSION)},SQLiteDatabase.CONFLICT_REPLACE);db.setTransactionSuccessful()}finally{db.endTransaction()}}
    }
    fun sources(building:String?=null):List<Source>{
        val list=mutableListOf<Source>()
        readableDatabase.query("sources",null,if(building!=null)"building=?" else null,building?.let{arrayOf(it)},null,null,"date DESC,id ASC").use{c->
            while(c.moveToNext())list+=Source(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4)?:"",c.getString(5)?:"",c.getString(6)?:"",c.getInt(7)==1,c.getInt(8)==1,c.getString(9)?:"")
        };return list
    }
    fun add(s:Source){
        val v=ContentValues().apply{put("id",s.id);put("path",s.path);put("type",s.type);put("building",s.building);put("asset",s.asset);put("date",s.date);put("title",s.title);put("synthetic",if(s.synthetic)1 else 0)}
        writableDatabase.insertWithOnConflict("sources",null,v,SQLiteDatabase.CONFLICT_IGNORE)
    }
    fun saveChunks(source:Source,chunks:List<VectorChunk>){
        require(chunks.isNotEmpty()){ "No searchable content found" }
        val db=writableDatabase;db.beginTransaction()
        try{
            db.delete("chunks","source=?",arrayOf(source.id))
            chunks.forEach{ch->
                require(ch.vector.size==768 && ch.vector.all{it.isFinite()}){"Invalid embedding"}
                val bytes=ByteBuffer.allocate(ch.vector.size*4).order(ByteOrder.LITTLE_ENDIAN);ch.vector.forEach{bytes.putFloat(it)}
                db.insertOrThrow("chunks",null,ContentValues().apply{put("source",source.id);put("ordinal",ch.ordinal);put("page",ch.page);put("start_ms",ch.startMs);put("text",ch.text);put("vector",bytes.array())})
            }
            db.update("sources",ContentValues().apply{put("indexed",1);put("error","")},"id=?",arrayOf(source.id));db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }
    fun error(id:String,message:String){writableDatabase.update("sources",ContentValues().apply{put("error",message.take(250))},"id=?",arrayOf(id))}
    fun search(query:FloatArray,building:String,limit:Int=40):List<SearchHit>{
        val items=sources(building).associateBy{it.id};val best=mutableMapOf<String,SearchHit>()
        readableDatabase.rawQuery("SELECT c.source,c.ordinal,c.page,c.start_ms,c.text,c.vector FROM chunks c JOIN sources s ON s.id=c.source WHERE s.building=?",arrayOf(building)).use{c->
            while(c.moveToNext()){
                val source=items[c.getString(0)]?:continue
                val buf=ByteBuffer.wrap(c.getBlob(5)).order(ByteOrder.LITTLE_ENDIAN);val vector=FloatArray(buf.remaining()/4){buf.float}
                val score=cosine(query,vector)
                val ch=VectorChunk(source.id,c.getInt(1),c.getInt(2),c.getInt(3),c.getString(4)?:"",vector)
                if(score.isFinite()&&(best[source.id]?.score?:-2f)<score)best[source.id]=SearchHit(source,ch,score)
            }
        };return best.values.sortedByDescending{it.score}.take(limit)
    }
    fun importPack(input:InputStream):Int{
        val stage=File(context.cacheDir,"pack-${UUID.randomUUID()}").apply{mkdirs()}
        try{
            var count=0;var bytes=0L
            ZipInputStream(input).use{zip->
                while(true){val entry=zip.nextEntry?:break;require(++count<=1000){"Too many pack entries"}
                    val name=entry.name.replace('\\','/');require(!name.startsWith('/')&&!name.contains(':')){"Invalid pack path"}
                    val target=File(stage,name).canonicalFile;require(target.path.startsWith(stage.canonicalPath+File.separator)){"Invalid pack path"}
                    if(entry.isDirectory){target.mkdirs();continue}
                    target.parentFile?.mkdirs();var fileBytes=0L
                    target.outputStream().use{out->val buffer=ByteArray(65536);while(true){val n=zip.read(buffer);if(n<0)break;fileBytes+=n;bytes+=n;require(fileBytes<=60_000_000&&bytes<=600_000_000){"Pack exceeds import limits"};out.write(buffer,0,n)}}
                }
            }
            val manifest=File(stage,"manifest.json");require(manifest.isFile){"Pack must contain manifest.json at its root"}
            val items=JSONObject(manifest.readText()).getJSONArray("items");require(items.length()<=500){"Too many sources"}
            val parsed=(0 until items.length()).map{i->
                val j=items.getJSONObject(i);val relative=j.getString("path");val f=File(stage,relative).canonicalFile
                require(f.path.startsWith(stage.canonicalPath+File.separator)&&f.isFile){"Missing or invalid source"}
                require(j.getString("type") in listOf("image","audio","pdf")){"Unsupported source type"}
                require(j.getString("building_id") in listOf("MC01","CH02")){"Unknown building"}
                j to f
            }
            val existing=sources().map{it.id}.toSet();val fresh=parsed.filter{it.first.getString("id") !in existing}
            require(fresh.map{it.first.getString("id")}.distinct().size==fresh.size){"Duplicate source IDs in pack"}
            if(fresh.isEmpty())return 0
            val dest=File(library,"pack-${UUID.randomUUID()}").apply{mkdirs()};val db=writableDatabase;db.beginTransaction();var succeeded=false
            try{
                fresh.forEach{(j,f)->val target=File(dest,j.getString("path"));target.parentFile?.mkdirs();f.copyTo(target)
                    add(Source(j.getString("id"),target.absolutePath,j.getString("type"),j.getString("building_id"),j.optString("asset_id"),j.optString("date"),j.optString("title",f.name),j.optBoolean("synthetic",false)))
                };db.setTransactionSuccessful();succeeded=true
            }finally{db.endTransaction();if(!succeeded)dest.deleteRecursively()}
            return fresh.size
        }finally{stage.deleteRecursively()}
    }
    companion object {
        fun cosine(a:FloatArray,b:FloatArray):Float{if(a.size!=b.size)return Float.NaN;var dot=0.0;var aa=0.0;var bb=0.0;for(i in a.indices){dot+=a[i]*b[i];aa+=a[i]*a[i];bb+=b[i]*b[i]};return if(aa>0&&bb>0)(dot/kotlin.math.sqrt(aa*bb)).toFloat() else Float.NaN}
    }
}

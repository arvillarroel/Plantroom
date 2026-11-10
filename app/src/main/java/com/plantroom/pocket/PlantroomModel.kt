package com.plantroom.pocket

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.*
import com.plantroom.pocket.runtime.GemmaEmbedder
import com.plantroom.pocket.ui.*
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.*
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class PlantroomModel(app:Application):AndroidViewModel(app){
    var state by mutableStateOf(UiState());private set
    private val appContext=app.applicationContext
    val store=Store(app)
    val embedder=GemmaEmbedder(app)
    private val worker=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val cancelIndex=AtomicBoolean(false)
    private val modelFile=File(app.filesDir,"models/${GemmaEmbedder.MODEL_FILENAME}")
    private val prefs=app.getSharedPreferences("plantroom",0)
    private var unfilteredSearchItems: List<UiItem> = emptyList()
    init{
        PDFBoxResourceLoader.init(app)
        task("Preparing your collection"){
            withContext(worker){
                store.ensureIndexVersion()
                if(!prefs.getBoolean("sample-v1",false)){
                    if(app.assets.list("")?.contains("sample-pack.zip")==true){app.assets.open("sample-pack.zip").use{store.importPack(it)};prefs.edit().putBoolean("sample-v1",true).apply()}
                }
                if(modelFile.isFile){verifyModel(modelFile);embedder.load(modelFile.path)}
            }
            state=state.copy(modelReady=embedder.isLoaded,status=if(embedder.isLoaded)"Model ready · all processing on this phone" else "Import the model to enable search")
            refresh()
        }
    }
    fun setBuilding(id:String){if(state.busy||state.recording)return;require(id in listOf("MC01","CH02"));unfilteredSearchItems=emptyList();state=state.copy(buildingId=id,query="",isSearching=false,searchMillis=null,filters=UiFilters(),filterError=null);refresh()}
    fun setQuery(q:String){if(state.busy||state.recording)return;state=state.copy(query=q.take(600),isSearching=false,searchMillis=null);refresh()}
    fun dismissError(){state=state.copy(error=null)}
    fun playback(id:String?){state=state.copy(playingId=id)}
    fun recording(active:Boolean){state=state.copy(recording=active)}
    fun messageError(message:String){state=state.copy(error=message)}
    /** Apply all constraints atomically. Invalid drafts never replace the current filters. */
    fun applyFilters(filters: UiFilters): Boolean {
        if (state.busy || state.recording) return false
        val normalized = filters.copy(dateFrom = filters.dateFrom.trim(), dateTo = filters.dateTo.trim())
        val validation = validateFilters(normalized, state.availableAssetIds)
        if (validation != null) { state = state.copy(filterError = validation); return false }
        state = state.copy(filters = normalized, filterError = null)
        refresh()
        return true
    }
    fun setKindFilter(kind: UiKind?) { applyFilters(state.filters.copy(kind = kind)) }
    fun resetFilters() { applyFilters(UiFilters()) }
    fun refresh(){
        val sources=store.sources(state.buildingId)
        val candidates = if (state.isSearching) unfilteredSearchItems else sources.map{it.ui(excerpt=if(it.error.isNotBlank())"Not indexed: ${it.error}" else "")}
        state=state.copy(totalCount=sources.size,indexedCount=sources.count{it.indexed},photoCount=sources.count{it.type=="image"},audioCount=sources.count{it.type=="audio"},reportCount=sources.count{it.type=="pdf"},
            availableAssetIds=sources.map{it.asset}.distinct().sorted(),items=filterItems(candidates,state.buildingId,state.filters))
    }
    private fun task(label:String,block:suspend()->Unit){
        if(state.busy||state.recording)return
        viewModelScope.launch{
            state=state.copy(busy=true,status=label,error=null)
            try{block()}catch(e:Exception){state=state.copy(error=e.message?.take(300)?:"Operation failed",status="Needs attention")}
            finally{state=state.copy(busy=false,progress=null,modelReady=embedder.isLoaded);refresh()}
        }
    }
    fun importModel(uri:Uri)=task("Importing model · this may take a moment"){
        withContext(worker){
            modelFile.parentFile!!.mkdirs();val temp=File(modelFile.parentFile,"model.partial")
            try{appContext.contentResolver.openInputStream(uri)!!.use{input->temp.outputStream().use{out->copyBounded(input,out,600_000_000)}};verifyModel(temp);embedder.close();temp.copyTo(modelFile,overwrite=true);embedder.load(modelFile.path)}finally{temp.delete()}
        }
        state=state.copy(modelReady=embedder.isLoaded,status="Model ready · index your collection next")
    }
    private fun verifyModel(file:File){
        require(file.length()==GemmaEmbedder.MODEL_BYTES){"Choose the full EmbeddingGemma 2 CPU/GPU model (485 MB)."}
        val digest=MessageDigest.getInstance("SHA-256");file.inputStream().use{input->val b=ByteArray(1024*1024);while(true){val n=input.read(b);if(n<0)break;digest.update(b,0,n)}}
        require(digest.digest().joinToString(""){"%02x".format(it)}==GemmaEmbedder.MODEL_SHA256){"Model checksum does not match the supported release."}
    }
    fun importPack(uri:Uri)=task("Importing building pack"){
        val count=withContext(worker){appContext.contentResolver.openInputStream(uri)!!.use{store.importPack(it)}}
        state=state.copy(isSearching=false,query="",searchMillis=null,status="$count sources imported · ready to index")
    }
    fun importFiles(uris:List<Uri>)=task("Importing files"){
        val building=state.buildingId;var count=0;val errors=mutableListOf<String>()
        withContext(worker){for(uri in uris.take(100)){try{importOne(uri,building);count++}catch(e:Exception){errors+=e.message?:"Unsupported file"}}}
        state=state.copy(query="",isSearching=false,searchMillis=null,status="$count files imported",error=errors.takeIf{it.isNotEmpty()}?.joinToString("; ")?.take(300))
    }
    private fun importOne(uri:Uri,building:String){
        val resolver=appContext.contentResolver
        val displayName=resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())it.getString(0)else null}?:"Imported file"
        val mime=resolver.getType(uri)?:""
        val type=when{mime.startsWith("image/")->"image";mime.startsWith("audio/")->"audio";mime=="application/pdf"||displayName.endsWith(".pdf",true)->"pdf";else->error("Unsupported file: $displayName")}
        val suffix=when(type){"image"->".img";"audio"->".audio";else->".pdf"};val id="USER-${UUID.randomUUID()}";val f=File(store.library,id+suffix)
        try{resolver.openInputStream(uri)!!.use{input->f.outputStream().use{out->copyBounded(input,out,50_000_000)}};store.add(Source(id,f.path,type,building,"",LocalDate.now().toString(),displayName,false))}catch(e:Exception){f.delete();throw e}
    }
    fun importRecording(file:File)=task("Saving voice note"){
        val building=state.buildingId
        withContext(worker){val id="USER-${UUID.randomUUID()}";val dest=File(store.library,"$id.m4a");file.copyTo(dest);store.add(Source(id,dest.path,"audio",building,"",LocalDate.now().toString(),"Voice note · ${LocalDate.now()}",false));file.delete()}
        state=state.copy(recording=false,isSearching=false,query="",searchMillis=null,status="Voice note saved · ready to index")
    }
    fun index()=task("Indexing on this phone"){
        check(embedder.isLoaded){"Import the model first"};cancelIndex.set(false)
        val pending=withContext(worker){store.sources(state.buildingId).filter{!it.indexed}}
        var failed=0;var completed=0
        for((i,s) in pending.withIndex()){
            if(cancelIndex.get())break
            state=state.copy(status="Indexing ${i+1} of ${pending.size} · ${s.title}",progress=i.toFloat()/pending.size.coerceAtLeast(1))
            try{withContext(worker){store.saveChunks(s,encodeSource(s))};completed++}catch(e:Exception){failed++;withContext(worker){store.error(s.id,e.message?:"Could not process source")}}
            refresh()
        }
        state=state.copy(status=if(cancelIndex.get())"Indexing paused · completed files are saved" else "$completed sources indexed${if(failed>0)" · $failed failed; inspect files and retry" else " · ready to search"}",error=if(failed>0)"$failed files could not be indexed. Their library entries show the reason." else null)
    }
    fun cancel(){cancelIndex.set(true);state=state.copy(status="Finishing current file, then pausing")}
    private fun encodeSource(s:Source):List<VectorChunk>{
        val file=File(s.path)
        return when(s.type){
            "image"->{val bitmap=MediaInputs.bitmap(file);try{listOf(VectorChunk(s.id,0,0,0,"",embedder.embedImage(bitmap)))}finally{bitmap.recycle()}}
            "audio"->{val pcm=MediaInputs.audio(file);val size=12*16000;val step=10*16000;val parts=mutableListOf<VectorChunk>();var from=0
                while(from<pcm.size){val until=minOf(from+size,pcm.size);parts+=VectorChunk(s.id,parts.size,0,from/16,"",embedder.embedAudio(pcm.copyOfRange(from,until).map{it.coerceIn(-1f,1f)}.toFloatArray()));if(until==pcm.size)break;from+=step};parts}
            else->{val parts=mutableListOf<VectorChunk>();PDDocument.load(file).use{doc->require(doc.numberOfPages<=100){"PDF limit is 100 pages"};val stripper=PDFTextStripper()
                for(page in 1..doc.numberOfPages){stripper.startPage=page;stripper.endPage=page;val text=stripper.getText(doc).trim();if(text.isBlank())continue
                    val words=text.split(Regex("\\s+"));var offset=0
                    while(offset<words.size){val end=minOf(offset+140,words.size);val chunk=words.subList(offset,end).joinToString(" ");parts+=VectorChunk(s.id,parts.size,page,0,chunk,embedder.embedText(chunk));if(end==words.size)break;offset+=110}
                }};require(parts.isNotEmpty()){"No selectable text in this PDF. Scanned PDFs are not supported yet."};parts}
        }
    }
    fun search() {if(state.query.isBlank()){state=state.copy(isSearching=false);refresh();return};val query=state.query
        task("Searching locally"){
            val start=System.nanoTime();val hits=withContext(worker){store.search(embedder.embedText(query,true),state.buildingId,state.totalCount)}
            showHits(hits,(System.nanoTime()-start)/1_000_000)
        }
    }
    fun imageSearch(uri:Uri)=task("Finding similar evidence"){
        val start=System.nanoTime();val temp=File(appContext.cacheDir,"query-image-${UUID.randomUUID()}")
        val hits=withContext(worker){try{appContext.contentResolver.openInputStream(uri)!!.use{input->temp.outputStream().use{out->copyBounded(input,out,30_000_000)}};val bitmap=MediaInputs.bitmap(temp);try{store.search(embedder.embedImage(bitmap),state.buildingId,state.totalCount)}finally{bitmap.recycle()}}finally{temp.delete()}}
        state=state.copy(query="Photo search");showHits(hits,(System.nanoTime()-start)/1_000_000)
    }
    private fun showHits(hits:List<SearchHit>,ms:Long){
        unfilteredSearchItems=hits.map{h->h.source.ui(h.score,sourceExcerpt(h.chunk.text,state.query),when(h.source.type){"pdf"->"Page ${h.chunk.page}";"audio"->"${h.chunk.startMs/60000}:${((h.chunk.startMs/1000)%60).toString().padStart(2,'0')}";else->"Original photo"})}
        state=state.copy(isSearching=true,items=filterItems(unfilteredSearchItems,state.buildingId,state.filters),searchMillis=ms,status=if(hits.isEmpty())"No indexed files in this building" else "Closest matches · inspect the original evidence")
    }
    // A literal window within the retrieved chunk, never a generated summary.
    // Ranking still uses the complete embedding; the original page remains one tap away.
    private fun sourceExcerpt(text:String,query:String):String {
        val words=text.split(Regex("\\s+")).filter{it.isNotEmpty()}
        val width=42
        if(words.size<=width)return text
        val stop=setOf("this","that","with","from","under","what","which","where","looking","find","photo","search")
        val terms=Regex("[\\p{L}\\p{N}]+").findAll(query.lowercase()).map{it.value}.filter{it.length>=4&&it !in stop}.toSet()
        val hits=words.map{word->if(Regex("[\\p{L}\\p{N}]+").findAll(word.lowercase()).any{it.value in terms})1 else 0}
        var best=0;var score=hits.take(width).sum();var bestScore=score
        for(start in 1..words.size-width){score+=hits[start+width-1]-hits[start-1];if(score>bestScore){best=start;bestScore=score}}
        val firstMatch=(best until minOf(best+width,words.size)).firstOrNull{hits[it]>0}
        if(firstMatch!=null)best=maxOf(best,firstMatch-5)
        val end=minOf(best+width,words.size)
        return (if(best>0)"… " else "")+words.subList(best,end).joinToString(" ")+(if(end<words.size)" …" else "")
    }
    override fun onCleared(){cancelIndex.set(true);worker.executor.execute{embedder.close();store.close()};worker.close();super.onCleared()}
    companion object {
        private fun validDate(value: String): LocalDate? = if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(value)) null else runCatching { LocalDate.parse(value) }.getOrNull()
        fun validateFilters(filters: UiFilters, availableAssetIds: List<String>): String? {
            if (filters.assetId != null && filters.assetId !in availableAssetIds) return "Choose an asset in the selected building."
            if (filters.dateFrom.isNotEmpty() && validDate(filters.dateFrom) == null) return "From date must be a real date in YYYY-MM-DD format."
            if (filters.dateTo.isNotEmpty() && validDate(filters.dateTo) == null) return "To date must be a real date in YYYY-MM-DD format."
            if (filters.dateFrom.isNotEmpty() && filters.dateTo.isNotEmpty() && filters.dateFrom > filters.dateTo) return "From date must be on or before the To date."
            return null
        }
        /** Stable filtering preserves the original similarity order; date bounds are inclusive. */
        fun filterItems(items: List<UiItem>, building: String, filters: UiFilters): List<UiItem> = items.filter { item ->
            item.buildingId == building &&
                (filters.assetId == null || item.assetId == filters.assetId) &&
                (filters.kind == null || item.kind == filters.kind) &&
                ((filters.dateFrom.isEmpty() && filters.dateTo.isEmpty()) ||
                    (validDate(item.date) != null && (filters.dateFrom.isEmpty() || item.date >= filters.dateFrom) &&
                        (filters.dateTo.isEmpty() || item.date <= filters.dateTo)))
        }
        fun copyBounded(input:java.io.InputStream,out:java.io.OutputStream,limit:Long){val buf=ByteArray(65536);var count=0L;while(true){val n=input.read(buf);if(n<0)break;count+=n;require(count<=limit){"File exceeds import size limit"};out.write(buf,0,n)}}
    }
}

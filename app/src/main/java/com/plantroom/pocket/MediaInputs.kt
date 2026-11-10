package com.plantroom.pocket

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.media.*
import java.io.File
import java.nio.ByteOrder
import kotlin.math.*

object MediaInputs {
    fun bitmap(file:File,maxEdge:Int=1024):Bitmap{
        val size=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(file.path,size)
        require(size.outWidth>0&&size.outHeight>0){"Image cannot be decoded"}
        var sample=1;while(max(size.outWidth,size.outHeight)/sample>maxEdge*2)sample*=2
        val decoded=BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply{inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888})?:error("Image cannot be decoded")
        val exif=try{ExifInterface(file)}catch(_:Exception){null}
        val matrix=Matrix()
        if(exif?.isFlipped==true)matrix.postScale(-1f,1f)
        if(exif!=null)matrix.postRotate(exif.rotationDegrees.toFloat())
        val raw=if(!matrix.isIdentity)Bitmap.createBitmap(decoded,0,0,decoded.width,decoded.height,matrix,true).also{if(it!==decoded)decoded.recycle()}else decoded
        val ratio=min(1f,maxEdge.toFloat()/max(raw.width,raw.height))
        return if(ratio<1)Bitmap.createScaledBitmap(raw,(raw.width*ratio).roundToInt().coerceAtLeast(1),(raw.height*ratio).roundToInt().coerceAtLeast(1),true).also{if(it!==raw)raw.recycle()} else raw
    }
    /** Decode native Android-supported audio and resample to 16kHz mono. Bound input to 60 seconds. */
    fun audio(file:File):FloatArray{
        val extractor=MediaExtractor();var codec:MediaCodec?=null
        try{
            extractor.setDataSource(file.path)
            val track=(0 until extractor.trackCount).firstOrNull{extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true}?:error("No audio track")
            val format=extractor.getTrackFormat(track);val duration=if(format.containsKey(MediaFormat.KEY_DURATION))format.getLong(MediaFormat.KEY_DURATION) else 0L
            require(duration<=60_000_000){"Voice notes must be 60 seconds or shorter"}
            extractor.selectTrack(track);val decoder=MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!);codec=decoder;decoder.configure(format,null,null,0);decoder.start()
            var rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE);var channels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);var encoding=AudioFormat.ENCODING_PCM_16BIT
            val chunks=ArrayList<FloatArray>();var total=0;var inputDone=false;var outputDone=false;val info=MediaCodec.BufferInfo();val deadline=System.currentTimeMillis()+120_000
            while(!outputDone){
                check(System.currentTimeMillis()<deadline){"Audio decoding timed out"}
                if(!inputDone){val index=decoder.dequeueInputBuffer(10000);if(index>=0){val buffer=decoder.getInputBuffer(index)!!;val n=extractor.readSampleData(buffer,0);if(n<0){decoder.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true}else{decoder.queueInputBuffer(index,0,n,extractor.sampleTime,0);extractor.advance()}}}
                val out=decoder.dequeueOutputBuffer(info,10000)
                if(out==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){val f=decoder.outputFormat;rate=f.getInteger(MediaFormat.KEY_SAMPLE_RATE);channels=f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);encoding=if(f.containsKey(MediaFormat.KEY_PCM_ENCODING))f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT}
                if(out>=0){val buffer=decoder.getOutputBuffer(out)!!;buffer.position(info.offset);buffer.limit(info.offset+info.size);buffer.order(ByteOrder.LITTLE_ENDIAN)
                    require(encoding==AudioFormat.ENCODING_PCM_16BIT||encoding==AudioFormat.ENCODING_PCM_FLOAT){"Unsupported PCM format"}
                    val frameBytes=(if(encoding==AudioFormat.ENCODING_PCM_FLOAT)4 else 2)*channels
                    val frames=FloatArray(info.size/frameBytes){var sum=0f;repeat(channels){sum+=if(encoding==AudioFormat.ENCODING_PCM_FLOAT)buffer.float else buffer.short/32768f};sum/channels}
                    total+=frames.size;require(total<=rate*61){"Audio too long"};chunks+=frames
                    outputDone=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0;decoder.releaseOutputBuffer(out,false)
                }
            }
            require(total>0){"Audio is empty"};val mono=FloatArray(total);var p=0;for(ch in chunks){ch.copyInto(mono,p);p+=ch.size}
            if(rate==16000)return mono
            return FloatArray((mono.size.toLong()*16000/rate).toInt()){i->val source=i.toDouble()*rate/16000;val left=source.toInt().coerceAtMost(mono.lastIndex);val right=(left+1).coerceAtMost(mono.lastIndex);(mono[left]+(mono[right]-mono[left])*(source-left)).toFloat()}
        }finally{try{codec?.stop()}catch(_:Exception){};codec?.release();extractor.release()}
    }
}

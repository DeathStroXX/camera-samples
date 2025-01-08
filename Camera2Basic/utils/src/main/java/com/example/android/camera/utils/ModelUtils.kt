
package com.example.android.camera.utils

//package your.package.name.util

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.channels.FileChannel
import java.nio.MappedByteBuffer
import java.nio.ByteBuffer

object ModelUtils {

    fun loadModelFile(context: Context, fileName: String): ByteBuffer {
        val assetManager = context.assets
        val fileDescriptor = assetManager.openFd("new_model.tflite")
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        return inputStream.channel.map(
            FileChannel.MapMode.READ_ONLY,
            fileDescriptor.startOffset,
            fileDescriptor.declaredLength
        ).also {
            inputStream.close()
        }
    }

    fun createInterpreter(context: Context, modelName: String): Interpreter {
        val modelBuffer = loadModelFile(context, modelName)
        return Interpreter(modelBuffer)
    }
}

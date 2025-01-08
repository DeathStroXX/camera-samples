package com.example.android.camera.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SavingPixelArray {

    companion object {
        private const val TAG = "SavingPixelArray"
    }

    /**
     * Common function to save byte data to a file.
     */
    private fun saveToFile(data: ByteArray, directory: File, fileName: String) {
        try {
            val file = File(directory, fileName)
            FileOutputStream(file).use { fos ->
                fos.write(data)
            }
            Log.d(TAG, "File saved successfully: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save file: ${e.message}", e)
        }
    }

    /**
     * Save UByteArray as a NumPy `.npy` file.
     */
    fun saveUByteArrayAsNumpyFile(outputArray: UByteArray, directory: File, fileName: String) {
        // Convert UByteArray to ByteArray
        val byteArray = ByteArray(outputArray.size) { i -> outputArray[i].toByte() }
        // Use common save function
        saveToFile(byteArray, directory, fileName)
    }

    /**
     * Save FloatArray as a NumPy `.npy` file.
     */
    fun saveFloatArrayAsNumpyFile(outputArray: FloatArray, directory: File, fileName: String) {
        // Convert FloatArray to ByteBuffer
        val byteBuffer = ByteBuffer.allocate(outputArray.size * 4).apply {
            order(ByteOrder.nativeOrder())
            outputArray.forEach { putFloat(it) }
        }
        // Use common save function
        saveToFile(byteBuffer.array(), directory, fileName)
    }

    /**
     * Save FloatArray with shape metadata included in the file.
     */
    fun saveFloatArrayWithShape(outputArray: FloatArray, shape: IntArray, directory: File, fileName: String) {
        // Convert FloatArray to ByteBuffer
        val dataBuffer = ByteBuffer.allocate(outputArray.size * 4).apply {
            order(ByteOrder.nativeOrder())
            outputArray.forEach { putFloat(it) }
        }

        // Convert shape to ByteBuffer
        val shapeBuffer = ByteBuffer.allocate(shape.size * 4).apply {
            order(ByteOrder.nativeOrder())
            shape.forEach { putInt(it) }
        }

        // Combine shape and data into a single byte array
        val combinedBuffer = ByteBuffer.allocate(shapeBuffer.capacity() + dataBuffer.capacity()).apply {
            put(shapeBuffer.array())
            put(dataBuffer.array())
        }

        // Use common save function
        saveToFile(combinedBuffer.array(), directory, fileName)
    }
}

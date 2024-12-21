
/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.camera2.basic.fragments

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
//import android.hardware.HardwareBuffer
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
//import androidx.annotation.RequiresApi
import androidx.core.graphics.drawable.toDrawable
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.Navigation
import androidx.navigation.fragment.navArgs
import com.example.android.camera.utils.ModelUtils
import com.example.android.camera.utils.computeExifOrientation
import com.example.android.camera.utils.getPreviewOutputSize
import com.example.android.camera.utils.OrientationLiveData
import com.example.android.camera2.basic.CameraActivity
import com.example.android.camera2.basic.R
import com.example.android.camera2.basic.databinding.FragmentCameraBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeoutException
import java.util.Date
import java.util.Locale
import kotlin.RuntimeException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class CameraFragment : Fragment() {

    /** Android ViewBinding */
    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val fragmentCameraBinding get() = _fragmentCameraBinding!!

    /** AndroidX navigation arguments */
    private val args: CameraFragmentArgs by navArgs()

    /** Host's navigation controller */
    private val navController: NavController by lazy {
        Navigation.findNavController(requireActivity(), R.id.fragment_container)
    }

    /** Detects, characterizes, and connects to a CameraDevice (used for all camera operations) */
    private val cameraManager: CameraManager by lazy {
        val context = requireContext().applicationContext
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    /** [CameraCharacteristics] corresponding to the provided Camera ID */
    private val characteristics: CameraCharacteristics by lazy {
        cameraManager.getCameraCharacteristics(args.cameraId)
    }

    /** Readers used as buffers for camera still shots */
    private lateinit var imageReader: ImageReader

    //For reading raw image
    private lateinit var imageReaderRAW: ImageReader

    // For DEPTH\
    private lateinit var imageReaderDepth: ImageReader
    //For model inferences
    private lateinit var interpreter: Interpreter


    /** [HandlerThread] where all camera operations run */
    private val cameraThread = HandlerThread("CameraThread").apply { start() }

    /** [Handler] corresponding to [cameraThread] */
    private val cameraHandler = Handler(cameraThread.looper)

    /** Performs recording animation of flashing screen */
    private val animationTask: Runnable by lazy {
        Runnable {
            // Flash white animation
            fragmentCameraBinding.overlay.background = Color.argb(150, 255, 255, 255).toDrawable()
            // Wait for ANIMATION_FAST_MILLIS
            fragmentCameraBinding.overlay.postDelayed({
                // Remove white flash animation
                fragmentCameraBinding.overlay.background = null
            }, CameraActivity.ANIMATION_FAST_MILLIS)
        }
    }

    /** [HandlerThread] where all buffer reading operations run */
    private val imageReaderThread = HandlerThread("imageReaderThread").apply { start() }

    /** [Handler] corresponding to [imageReaderThread] */
    private val imageReaderHandler = Handler(imageReaderThread.looper)

    /** The [CameraDevice] that will be opened in this fragment */
    private lateinit var camera: CameraDevice

    /** Internal reference to the ongoing [CameraCaptureSession] configured with our parameters */
    private lateinit var session: CameraCaptureSession

    /** Live data listener for changes in the device orientation relative to the camera */
    private lateinit var relativeOrientation: OrientationLiveData

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        try {
            interpreter = ModelUtils.createInterpreter(requireContext(), "model.tflite")
            Log.d("Model", "Interpreter initialized successfully.")
        } catch (e: Exception) {
            Log.e("Model", "Failed to initialize interpreter: ${e.message}")
        }


        fragmentCameraBinding.captureButton.setOnApplyWindowInsetsListener { v, insets ->
            v.translationX = (-insets.systemWindowInsetRight).toFloat()
            v.translationY = (-insets.systemWindowInsetBottom).toFloat()
            insets.consumeSystemWindowInsets()
        }

        fragmentCameraBinding.viewFinder.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int) = Unit

            override fun surfaceCreated(holder: SurfaceHolder) {
                // Selects appropriate preview size and configures view finder
                val previewSize = getPreviewOutputSize(
                    fragmentCameraBinding.viewFinder.display,
                    characteristics,
                    SurfaceHolder::class.java
                )
                Log.d(TAG, "View finder size: ${fragmentCameraBinding.viewFinder.width} x ${fragmentCameraBinding.viewFinder.height}")
                Log.d(TAG, "Selected preview size: $previewSize")
                fragmentCameraBinding.viewFinder.setAspectRatio(
                    previewSize.width,
                    previewSize.height
                )

                // To ensure that size is set, initialize camera in the view's thread
                view.post { initializeCamera() }
            }
        })

        // Used to rotate the output media to match device orientation
        relativeOrientation = OrientationLiveData(requireContext(), characteristics).apply {
            observe(viewLifecycleOwner, Observer { orientation ->
                Log.d(TAG, "Orientation changed: $orientation")
            })
        }
    }

    /**
     * Begin all camera operations in a coroutine in the main thread. This function:
     * - Opens the camera
     * - Configures the camera session
     * - Starts the preview by dispatching a repeating capture request
     * - Sets up the still image capture listeners
     */
    private fun initializeCamera() = lifecycleScope.launch(Dispatchers.Main) {

        //Loading the model
//       interpreter = ModelUtils.createInterpreter(requireContext(), "model.tflite")
//        Log.d("Model", "Model loaded successfully.")

        // Open the selected camera
        camera = openCamera(cameraManager, args.cameraId, cameraHandler)

        // Initialize an image reader which will be used to capture still photos
        val size = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            .getOutputSizes(args.pixelFormat).maxByOrNull { it.height * it.width }!!
        imageReader = ImageReader.newInstance(
            size.width, size.height, args.pixelFormat, IMAGE_BUFFER_SIZE)


        // RAW ImageReader
            //ra
        val rawSizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.RAW_SENSOR)

        if (rawSizes != null && rawSizes.isNotEmpty()) {
            val rawSize = rawSizes.maxByOrNull { it.height * it.width }!!
            imageReaderRAW = ImageReader.newInstance(
                rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 5)   // rawSize 2304x1728
            Log.d("RawImageReader", "Height: ${rawSize.height} and with${rawSize.width}")

        }


        //Adding both raw and jpeg to the targets
        val targets = listOf(fragmentCameraBinding.viewFinder.holder.surface, imageReader.surface, imageReaderRAW.surface)

        // Start a capture session using our open camera and list of Surfaces where frames will go
        session = createCaptureSession(camera, targets, cameraHandler)

        val captureRequest = camera.createCaptureRequest(
            CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(fragmentCameraBinding.viewFinder.holder.surface) }

        // This will keep sending the capture request as frequently as possible until the
        // session is torn down or session.stopRepeating() is called
        session.setRepeatingRequest(captureRequest.build(), null, cameraHandler)
        Log.d("CaptureRequest", "Capture request sent.")
        // Listen to the capture button
        fragmentCameraBinding.captureButton.setOnClickListener {

            // Disable click listener to prevent multiple requests simultaneously in flight
            it.isEnabled = false


            // Perform I/O heavy operations in a different scope
            lifecycleScope.launch(Dispatchers.IO) {
                takePhoto().use { result ->
                    Log.d(TAG, "Result received: $result")


// Save the result to disk
                    val output = saveResult(result)
                    Log.d(TAG, "Image saved: ${output.absolutePath}")


                    // If the result is a JPEG file, update EXIF metadata with orientation info
                    if (output.extension == "jpg") {
                        val exif = ExifInterface(output.absolutePath)
                        exif.setAttribute(
                            ExifInterface.TAG_ORIENTATION, result.orientation.toString())
                        exif.saveAttributes()
                        Log.d(TAG, "EXIF metadata saved: ${output.absolutePath}")
                    }




                    // Display the photo taken to user
                    lifecycleScope.launch(Dispatchers.Main) {
                        navController.navigate(CameraFragmentDirections
                            .actionCameraToJpegViewer(output.absolutePath)
                            .setOrientation(result.orientation)
                            .setDepth(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                                    result.format == ImageFormat.DEPTH_JPEG))
                    }
                }

                // Re-enable click listener after photo is taken
                it.post { it.isEnabled = true }
            }
        }
    }

    /** Opens the camera and returns the opened device (as the result of the suspend coroutine) */
    @SuppressLint("MissingPermission")
    private suspend fun openCamera(
        manager: CameraManager,
        cameraId: String,
        handler: Handler? = null
    ): CameraDevice = suspendCancellableCoroutine { cont ->
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) = cont.resume(device)

            override fun onDisconnected(device: CameraDevice) {
                Log.w(TAG, "Camera $cameraId has been disconnected")
                requireActivity().finish()
            }

            override fun onError(device: CameraDevice, error: Int) {
                val msg = when (error) {
                    ERROR_CAMERA_DEVICE -> "Fatal (device)"
                    ERROR_CAMERA_DISABLED -> "Device policy"
                    ERROR_CAMERA_IN_USE -> "Camera in use"
                    ERROR_CAMERA_SERVICE -> "Fatal (service)"
                    ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                    else -> "Unknown"
                }
                val exc = RuntimeException("Camera $cameraId error: ($error) $msg")
                Log.e(TAG, exc.message, exc)
                if (cont.isActive) cont.resumeWithException(exc)
            }
        }, handler)
    }

    /**
     * Starts a [CameraCaptureSession] and returns the configured session (as the result of the
     * suspend coroutine
     */
    private suspend fun createCaptureSession(
        device: CameraDevice,
        targets: List<Surface>,
        handler: Handler? = null
    ): CameraCaptureSession = suspendCoroutine { cont ->

        // Create a capture session using the predefined targets; this also involves defining the
        // session state callback to be notified of when the session is ready
        device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {

            override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)

            override fun onConfigureFailed(session: CameraCaptureSession) {
                val exc = RuntimeException("Camera ${device.id} session configuration failed")
                Log.e(TAG, exc.message, exc)
                cont.resumeWithException(exc)
            }
        }, handler)
    }

    /**
     * Helper function used to capture a still image using the [CameraDevice.TEMPLATE_STILL_CAPTURE]
     * template. It performs synchronization between the [CaptureResult] and the [Image] resulting
     * from the single capture, and outputs a [CombinedCaptureResult] object.
     */
    private suspend fun takePhoto():
            CombinedCaptureResult = suspendCoroutine { cont ->

        // Flush any images left in the image reader
        @Suppress("ControlFlowWithEmptyBody")
        while (imageReader.acquireNextImage() != null) {
        }
        //Adding for


        // Start a new image queue
        val imageQueue = ArrayBlockingQueue<Image>(IMAGE_BUFFER_SIZE)

        val rawImageQueue = ArrayBlockingQueue<Image>(IMAGE_BUFFER_SIZE)  // Adding for raw

//        val depthImageQueue = ArrayBlockingQueue<Image>(IMAGE_BUFFER_SIZE)  // Ading for depth

        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage()
            Log.d(TAG, "Image available in queue: ${image.timestamp}")
            imageQueue.add(image)
        }, imageReaderHandler)

//        Adding for Raw
//        imageReaderRAW.setOnImageAvailableListener({ reader ->
//            val image = reader.acquireNextImage()
//            rawImageQueue.add(image)
//        }, imageReaderHandler)

        imageReaderRAW.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage()
            if (image != null) {
                Log.d(TAG, "RAW Image acquired: ${image.timestamp}")
                Log.d(TAG, "RAW Image acquired: Timestamp = ${image.timestamp}, Width = ${image.width}, Height = ${image.height}")
                rawImageQueue.add(image)
            } else {
                Log.e(TAG, "RAW Image is null.")
            }
        }, imageReaderHandler)

        val captureRequest = session.device.createCaptureRequest(
            CameraDevice.TEMPLATE_STILL_CAPTURE).apply { addTarget(imageReader.surface) }
        session.capture(captureRequest.build(), object : CameraCaptureSession.CaptureCallback() {

            override fun onCaptureStarted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                timestamp: Long,
                frameNumber: Long) {
                super.onCaptureStarted(session, request, timestamp, frameNumber)
                fragmentCameraBinding.viewFinder.post(animationTask)
            }

            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult) {
                super.onCaptureCompleted(session, request, result)
                val resultTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
                Log.d(TAG, "Capture result received: $resultTimestamp")

                // Set a timeout in case image captured is dropped from the pipeline
                val exc = TimeoutException("Image dequeuing took too long")
                val timeoutRunnable = Runnable { cont.resumeWithException(exc) }
                imageReaderHandler.postDelayed(timeoutRunnable, IMAGE_CAPTURE_TIMEOUT_MILLIS)

                // Loop in the coroutine's context until an image with matching timestamp comes
                // We need to launch the coroutine context again because the callback is done in
                //  the handler provided to the `capture` method, not in our coroutine context
                @Suppress("BlockingMethodInNonBlockingContext")
                lifecycleScope.launch(cont.context) {
                    while (true) {

                        // Dequeue images while timestamps don't match
                        val image = imageQueue.take()
                        // TODO(owahltinez): b/142011420
                        // if (image.timestamp != resultTimestamp) continue
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                            image.format != ImageFormat.DEPTH_JPEG &&
                            image.timestamp != resultTimestamp) continue
                        Log.d(TAG, "Matching image dequeued: ${image.timestamp}")

                        // Unset the image reader listener
                        imageReaderHandler.removeCallbacks(timeoutRunnable)
                        imageReader.setOnImageAvailableListener(null, null)

                        // Clear the queue of images, if there are left
                        while (imageQueue.size > 0) {
                            imageQueue.take().close()
                        }

                        // Compute EXIF orientation metadata
                        val rotation = relativeOrientation.value ?: 0
                        val mirrored = characteristics.get(CameraCharacteristics.LENS_FACING) ==
                                CameraCharacteristics.LENS_FACING_FRONT
                        val exifOrientation = computeExifOrientation(rotation, mirrored)

                        // Build the result and resume progress
                        cont.resume(CombinedCaptureResult(
                            image, result, exifOrientation, imageReader.imageFormat))

                        // There is no need to break out of the loop, this coroutine will suspend
                    }
                }
            }
        }, cameraHandler)
    }


    private suspend fun saveResult(result: CombinedCaptureResult): File = suspendCoroutine { cont ->
        when (result.format) {
            ImageFormat.JPEG, ImageFormat.DEPTH_JPEG -> {
                // Save JPEG file
                val buffer = result.image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining()).apply { buffer.get(this) }
                try {
                    val jpgFile = createFile(requireContext(), "jpg")
                    FileOutputStream(jpgFile).use { it.write(bytes) }

                    cont.resume(jpgFile)
                } catch (exc: IOException) {
                    Log.e(TAG, "Unable to write JPEG image to file", exc)
                    cont.resumeWithException(exc)
                }
            }

            ImageFormat.RAW_SENSOR -> {
                // Handle RAW_SENSOR image format
//
                val dngFile = createFile(requireContext(), "dng")
                try {
                    val dngCreator = DngCreator(characteristics, result.metadata)

                    FileOutputStream(dngFile).use { dngCreator.writeImage(it, result.image) }
                    cont.resume(dngFile)

                    // Save metadata as JSON
                    val metadataFile = saveMetadata(result.metadata, dngFile)
                    Log.d(TAG, "Metadata saved: ${metadataFile.absolutePath}")

                    // Convert RAW to JPEG and save performing minor image processing
                    val (jpegFile, bitmap) = convertRawToJpeg(dngFile)
                    Log.d(TAG, "JPEG image saved: ${jpegFile.absolutePath}")

                    // Ensure the Bitmap is not null before running inference
                    if (bitmap != null) {
                        runInferenceOnBitmap(bitmap, interpreter)
                    } else {
                        Log.e("ImageProcessing", "Failed to decode RAW image to Bitmap.")
                    }
                } catch (exc: IOException) {
                    Log.e(TAG, "Unable to write DNG image to file", exc)
                    cont.resumeWithException(exc)
                }
            }

            ImageFormat.DEPTH_JPEG -> {
                try {
                    // Save the JPEG visual image
                    val buffer = result.image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining()).apply { buffer.get(this) }
                    val jpgFile = createFile(requireContext(), "jpg")
                    FileOutputStream(jpgFile).use { it.write(bytes) }
                    Log.d(TAG, "JPEG saved: ${jpgFile.absolutePath}")

                    // Save the depth map (second plane)
                    val depthBuffer = result.image.planes[1].buffer
                    val depthBytes =
                        ByteArray(depthBuffer.remaining()).apply { depthBuffer.get(this) }
                    val depthFile = createFile(requireContext(), "depth")
                    FileOutputStream(depthFile).use { it.write(depthBytes) }
                    Log.d(TAG, "Depth map saved: ${depthFile.absolutePath}")

                    // Resume coroutine with the JPEG file (primary file)
                    cont.resume(jpgFile)

                } catch (exc: IOException) {
                    Log.e(TAG, "Unable to write Depth JPEG files", exc)
                    cont.resumeWithException(exc)
                }
            }

            else -> {
                val exc = RuntimeException("Unknown image format: ${result.image.format}")
                Log.e(TAG, exc.message, exc)
                cont.resumeWithException(exc)
            }
        }
    }

    //created this helper function to convert raw image to jpeg
    private fun convertRawToJpeg(rawFile: File): Pair<File, Bitmap?> {
        // Decode the RAW file
        val inputStream = FileInputStream(rawFile)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeStream(inputStream, null, options)
        inputStream.close()

        // Create a new JPEG file
        val jpegFile = createFile(requireContext(), "jpg")
        FileOutputStream(jpegFile).use { outputStream ->
            bitmap?.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
        }

        return Pair(jpegFile, bitmap)
    }


    //Helper function created to capture all the metadata information
    private fun saveMetadata(metadata: CaptureResult, dngFile: File): File {
        val metadataMap = mutableMapOf<String, Any?>()

        for (key in metadata.keys) {
            metadataMap[key.name] = metadata.get(key)
        }

        val metadataFile = File(dngFile.parent, "${dngFile.nameWithoutExtension}_metadata.json")
        FileWriter(metadataFile).use {
            it.write(JSONObject(metadataMap).toString(4))
        }
        return metadataFile
    }
    // Function to run inferences and to extract the raw pixel directly from the planes
    fun runInferenceOnBitmap(bitmap: Bitmap, interpreter: Interpreter) {
        try {
            // Convert the Bitmap to a FloatArray for inference
            val inputArray = preprocessBitmapToModelInput(bitmap)

//            saveInputArrayAsImage(inputArray, 2304, 1728)
            Log.d("ModelInput", "Input array size: ${inputArray.size}")
            // Log the values in a formatted string
//            val valuesString = inputArray.joinToString(", ") { it.toString() }
//            Log.d("InputArrayValues", "Values: $valuesString")

            // Get the output tensor and its shape
            val outputTensor = interpreter.getOutputTensor(0)
            val outputShape = outputTensor.shape()

            // Calculate the total number of elements in the output tensor
            val outputSize = outputShape.reduce { acc, dim -> acc * dim }

            // Create an appropriately sized output array
            val outputArray = Array(outputShape[0]) { ByteArray(outputSize) }
            Log.d("OutputArraySize", "Dimensions: ${outputArray.size} x ${outputArray[0].size}")
            // Run inference
            interpreter.run(arrayOf(inputArray), outputArray)
            Log.d("Inference", "Inference completed successfully.")

            // Process the output(example: find the predicted class)
            val predictedClass = outputArray[0].withIndex().maxByOrNull { it.value }?.index
            Log.d("ModelPrediction", "Predicted class: $predictedClass")
            if (outputArray.isNotEmpty()) {
                Log.d("OutputCheck", "Sample Output Values: ${outputArray[0].take(10)}")
            }
            // Making changes for saving image
            val outputArray1D = outputArray.flatMap { it.asIterable() }.toByteArray()
//
            // Log the size of the 1D output array
            Log.d("OutputArray1D", "Flattened output array size: ${outputArray1D.size}")

            // Log a subset of the values to verify contents (first 100 values or less)
            val logValues = outputArray1D.take(100).joinToString(", ")
            Log.d("OutputArray1DValues", "First 100 values: [$logValues]")
            // After running inference
            saveProcessedOutput(outputArray1D, outputShape)

        } catch (e: Exception) {
            Log.e("ModelError", "Error during inference: ${e.message}")
        }
    }

    // Assuming you have a preprocessBitmapToModelInput function that converts the Bitmap

    private fun preprocessBitmapToModelInput(bitmap: Bitmap): FloatArray {

        // Resize the bitmap to the model's expected input size
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, 2304, 1728, true)
        Log.d("Preprocess", "Bitmap resized to: ${resizedBitmap.width}x${resizedBitmap.height}")

        // Initialize the FloatArray for the RGB input
        val floatArray = FloatArray(2304 * 1728 * 3) // For RGB input
        var index = 0

        // Debugging variables to track pixel values
        var minPixelValue = Float.MAX_VALUE
        var maxPixelValue = Float.MIN_VALUE
        Log.d("Model Input--------", "Total values in float array: ${minPixelValue}, ${maxPixelValue}")

        for (y in 0 until resizedBitmap.height) {
            for (x in 0 until resizedBitmap.width) {
                val pixel = resizedBitmap.getPixel(x, y)
                val r = (pixel shr 16 and 0xFF) / 255.0f // Red
                val g = (pixel shr 8 and 0xFF) / 255.0f  // Green
                val b = (pixel and 0xFF) / 255.0f        // Blue

                // Store the normalized RGB values in the float array
                floatArray[index++] = r
                floatArray[index++] = g
                floatArray[index++] = b

                // Update min and max for debugging
                minPixelValue = minOf(minPixelValue, r, g, b)
                maxPixelValue = maxOf(maxPixelValue, r, g, b)
            }
        }

        // Log statistics about the processed input
        Log.d("Preprocess", "Preprocessed float array created.")
        Log.d("Preprocess", "Total values: ${floatArray.size}, Min: $minPixelValue, Max: $maxPixelValue")

        // Log a sample of the normalized pixel values
        Log.d("PreprocessSample", "Sample values: ${floatArray.take(10)}")

        return floatArray
    }

    fun saveProcessedOutput(outputArray: ByteArray, outputShape: IntArray) {
        val width = outputShape[3]
        val height = outputShape[2]
        val channels = outputShape[1]

        if (channels == 3) { // Assuming RGB output
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(width * height)

            for (y in 0 until height) {
                for (x in 0 until width) {
                    val r = (outputArray[(0 * height * width) + (y * width) + x] * 255).toInt().coerceIn(0, 255)
                    val g = (outputArray[(1 * height * width) + (y * width) + x] * 255).toInt().coerceIn(0, 255)
                    val b = (outputArray[(2 * height * width) + (y * width) + x] * 255).toInt().coerceIn(0, 255)
                    pixels[y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }

            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

            // Save the bitmap to a file
            val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
            val fileName = "IMG_${sdf.format(Date())}_ProcessedOutput.jpg"
            val file = File(requireContext().filesDir, fileName)
            FileOutputStream(file).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos)
            }

            Log.d("ProcessedOutput", "Processed image saved: ${file.absolutePath}")
        } else {
            Log.e("ProcessedOutput", "Unsupported output channels: $channels")
        }
    }
    private fun saveInputArrayAsImage(inputArray: FloatArray, width: Int, height: Int) {
        try {
            // Create a Bitmap to store the pixel data

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            var index = 0

            for (y in 0 until height) {
                for (x in 0 until width) {
                    // Extract RGB values from the FloatArray
                    val r = (inputArray[index++] * 255).toInt().coerceIn(0, 255)
                    val g = (inputArray[index++] * 255).toInt().coerceIn(0, 255)
                    val b = (inputArray[index++] * 255).toInt().coerceIn(0, 255)

                    // Reconstruct the pixel color
                    val color = (255 shl 24) or (r shl 16) or (g shl 8) or b
                    bitmap.setPixel(x, y, color)
                }
            }

            // Save the reconstructed Bitmap to a file
            val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
            val outputFile = File(requireContext().filesDir, "IMG_${sdf.format(Date())}_Input.jpg")
            FileOutputStream(outputFile).use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
            }

            Log.d("SaveImage", "Input array saved as image: ${outputFile.absolutePath}")
        } catch (e: Exception) {
            Log.e("SaveImageError", "Failed to save input array as image: ${e.message}")
        }
    }










    override fun onStop() {
        super.onStop()
        try {
            camera.close()
        } catch (exc: Throwable) {
            Log.e(TAG, "Error closing camera", exc)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraThread.quitSafely()
        imageReaderThread.quitSafely()

        interpreter.close()
        Log.d("Model", "Interpreter closed.")
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()
    }

    companion object {
        private val TAG = CameraFragment::class.java.simpleName

        /** Maximum number of images that will be held in the reader's buffer */
        private const val IMAGE_BUFFER_SIZE: Int = 3

        /** Maximum time allowed to wait for the result of an image capture */
        private const val IMAGE_CAPTURE_TIMEOUT_MILLIS: Long = 5000

        /** Helper data class used to hold capture metadata with their associated image */
        data class CombinedCaptureResult(
            val image: Image,
            val metadata: CaptureResult,
            val orientation: Int,
            val format: Int
        ) : Closeable {
            override fun close() = image.close()
        }

        /**
         * Create a [File] named a using formatted timestamp with the current date and time.
         *
         * @return [File] created.
         */
        private fun createFile(context: Context, extension: String): File {
            val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
            return File(context.filesDir, "IMG_${sdf.format(Date())}.$extension")
        }



    }
}

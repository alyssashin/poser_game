package com.example.poser

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.android.synthetic.main.activity_main.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.Exception
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import kotlin.random.Random.Default.nextInt

typealias TypeListener = (faceclassification: Int) -> Unit

class MainActivity : AppCompatActivity() {
    private var imageCapture: ImageCapture? = null
    private var needUpdateGraphicOverlayImageSourceInfo = false

    private lateinit var outputDirectory: File
    private lateinit var cameraExecutor: ExecutorService



    //generate random numbers that represent the facial expression that the user is supposed to make
    //0 = smiling
    //1 = left eye open
    //2 = right eye open
    var randomValues = intArrayOf(nextInt(0,2), nextInt(0,2), nextInt(0,2))

    //analyze face
    private class FaceAnalyzer(private val listener: TypeListener) : ImageAnalysis.Analyzer {
        // MLKit face options examples
        // High-accuracy landmark detection and face classification
        val highAccuracyOpts = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .enableTracking()
            .build()
        // Real-time contour detection
        val realTimeOpts = FaceDetectorOptions.Builder()
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .build()
        private fun ImageProxy.toBitmap() : Bitmap?{
            val nv21 = BitmapUtils.yuv420888ToNv21(this)
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            return yuvImage.toBitmap()
        }

        private fun YuvImage.toBitmap(): Bitmap? {
            val out = ByteArrayOutputStream()
            if (!compressToJpeg(Rect(0, 0, width, height), 100, out))
                return null
            val imageBytes: ByteArray = out.toByteArray()
            return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        }

        private fun ByteBuffer.toByteArray(): ByteArray {
            rewind()    // Rewind the buffer to zero
            val data = ByteArray(remaining())
            get(data)   // Copy the buffer into a byte array
            return data // Return the byte array
        }

        //threshold and the match that is observed
        var match = 0;
        val THRESHOLD = 0.7f

        val detector = FaceDetection.getClient(highAccuracyOpts)

        override fun analyze(imageProxy: ImageProxy) {
            imageProxy?.let{
                try{
                    it.toBitmap()?.let{bm->
                        detector.process(InputImage.fromBitmap(bm, 0)).addOnSuccessListener {faces->

                            for (face in faces) {
                                //get probabilities of each classifier
                                val smileProb = face.smilingProbability!!
                                val rightEyeOpenProb = face.rightEyeOpenProbability!!
                                val leftEyeOpenProb = face.leftEyeOpenProbability!!

                                Log.d(TAG, "smilrProb $smileProb")
                                Log.d(TAG, "rightEyeOpenProb $rightEyeOpenProb")
                                Log.d(TAG, "leftEyeOpenProb $leftEyeOpenProb")

                                //calculate total score
                                val totalscore = decideValues(smileProb, rightEyeOpenProb, leftEyeOpenProb, THRESHOLD)
                                match = totalscore

                                listener(match)
                            }
                        }.addOnFailureListener {e->
                            Log.d(TAG, "process fail")
                            e.printStackTrace()
                        }
                    }
                } catch (e: MlKitException) {
                   // PrintLog.error(FrameAnalyzer::class.java, "Failed to process image. Error: " + e.localizedMessage)
                }catch(e: IllegalStateException){
                    //PrintLog.error(FrameAnalyzer::class.java, e.toString())
                }
            }
            imageProxy.close()
        }


        //evaluate the score, and whether the match is passable or not
        private fun decideValues(smile: Float, rightEye: Float, leftEye: Float, threshold: Float) : Int {
            var totalscore = 0;

            if (smile > threshold) totalscore ++
            if (rightEye > threshold) totalscore ++
            if (leftEye > threshold) totalscore ++

            Log.d(TAG,"$totalscore")

            return totalscore
        }
    }

    private fun showScores(score: Int) {
        runOnUiThread {
            score_display.text = "Your current score is: "+"$score"
        }
    }

    //click listener for shuffle button
    val clickListener = View.OnClickListener { view ->
        when (view.getId()) {
            R.id.button -> {
                shuffle()
            }
        }
    }

    //on creating the activity
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        //need to call shuffle to generate command and display
        shuffle()

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                    this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        // set up the listener for take photo button
        camera_capture_button.setOnClickListener { takePhoto() }
        outputDirectory = getOutputDirectory()
        cameraExecutor = Executors.newSingleThreadExecutor()

        //set on click listener for the shuffle command button
        val shufflebutton = findViewById<Button>(R.id.button)
        shufflebutton.setOnClickListener(clickListener)
    }

    //shuffle random int
    private fun shuffle(){
        randomValues = intArrayOf(nextInt(0, 2), nextInt(0,2), nextInt(0,2))
        updateCommand()
    }

    //updates the screen
    private fun updateCommand() {
        runOnUiThread {
            pose_name.text = translateArray(randomValues)
        }
    }

    //translate the random array into words
    private fun translateArray(numberCommand: IntArray): String{
        val smiling = numberCommand.get(0);
        val lefteye = numberCommand.get(1);
        val righteye = numberCommand.get(2);

        val a = evaluateNum(smiling) + " Smile"
        val b = evaluateNum(lefteye) + " Open Left Eye"
        val c = evaluateNum(righteye) + " Open Right Eye"

        return a+" "+b+" "+c
    }

    //helper function for translating from array to string
    private fun evaluateNum(state: Int): String{
        if (state == 0) {
            return "Don't"
        }
        return "Do"
    }

    //for starting camera
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener(Runnable{
            //used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            //preview
            val preview = Preview.Builder()
                    .build()
                    .also{
                        it.setSurfaceProvider(viewFinder.createSurfaceProvider())
                    }

            imageCapture = ImageCapture.Builder()
                    .build()

            //build image analyzer with correct properties and target resolution size
            val analysisUseCase  = ImageAnalysis.Builder()
                .setTargetResolution(Size(480, 360))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            needUpdateGraphicOverlayImageSourceInfo = true

            analysisUseCase.setAnalyzer(
                ContextCompat.getMainExecutor(this),
                FaceAnalyzer {reslt->
                    Log.d(TAG, reslt.toString())
                }
            )

            //use front camera as default, as you're going to be analyzing faces
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture, analysisUseCase)
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    //for taking photos
    private fun takePhoto() {
        // Get a stable reference of the modifiable image capture use case
        val imageCapture = imageCapture ?: return

        // Create time-stamped output file to hold the image
        val photoFile = File(
                outputDirectory,
                SimpleDateFormat(FILENAME_FORMAT, Locale.US
                ).format(System.currentTimeMillis()) + ".jpg")

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        // Set up image capture listener, which is triggered after photo has
        // been taken
        imageCapture.takePicture(
                outputOptions, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onError(exc: ImageCaptureException) {
                Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
            }

            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val savedUri = Uri.fromFile(photoFile)
                val msg = "Photo capture succeeded: $savedUri"
                Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                Log.d(TAG, msg)
            }
        })

        Toast.makeText(this,
                "You just took a photo of your wonderful facial expression!",
                Toast.LENGTH_SHORT).show()
    }

    private fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, resources.getString(R.string.app_name)).apply { mkdirs() } }
        return if (mediaDir != null && mediaDir.exists())
            mediaDir else filesDir
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraXBasic"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private const val FACE_DETECTION = "Face Detection"
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
            requestCode: Int, permissions: Array<String>, grantResults:
            IntArray) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this,
                        "Permissions not granted by the user.",
                        Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

}
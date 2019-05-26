package org.twodee.twoface

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.os.Environment
import android.util.*
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import androidx.camera.core.*
import org.twodee.rattler.PermittedActivity
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

class MainActivity : PermittedActivity() {
  private lateinit var captureUseCase: ImageCapture

  private lateinit var previewView: TextureView
  private lateinit var leftImageView: ImageView
  private lateinit var rightImageView: ImageView

  private lateinit var leftResetButton: ImageButton
  private lateinit var rightResetButton: ImageButton
  private lateinit var leftCaptureButton: ImageButton
  private lateinit var rightCaptureButton: ImageButton

  private var leftBitmap: Bitmap? = null
  private var rightBitmap: Bitmap? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    leftImageView = findViewById(R.id.leftImageView)
    rightImageView = findViewById(R.id.rightImageView)

    leftResetButton = findViewById(R.id.leftResetButton)
    rightResetButton = findViewById(R.id.rightResetButton)
    leftCaptureButton = findViewById(R.id.leftCaptureButton)
    rightCaptureButton = findViewById(R.id.rightCaptureButton)

    previewView = findViewById(R.id.leftView)

    leftImageView.visibility = View.INVISIBLE
    rightImageView.visibility = View.INVISIBLE
    leftResetButton.visibility = View.INVISIBLE
    rightResetButton.visibility = View.INVISIBLE

    requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE), 100, {
      previewView.post {
        initializeUseCases()
      }
      registerCallbacks()
    }, {
      Log.d("FOO", "Bad...")
    })

    window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
  }

  private fun registerCallbacks() {
    leftCaptureButton.setOnClickListener {
      takeLeftImage()
    }

    rightCaptureButton.setOnClickListener {
      takeRightImage()
    }

    leftResetButton.setOnClickListener {
      leftBitmap = null
      syncLeft()
    }

    rightResetButton.setOnClickListener {
      rightBitmap = null
      syncRight()
    }
  }

  private fun sizeToCover(frame: View, image: Bitmap): SizeF {
    val frameAspect = frame.width / frame.height.toFloat()
    val imageAspect = image.width / image.height.toFloat()

    val scaledWidth: Float
    val scaledHeight: Float

    if (frameAspect >= imageAspect) {
      scaledWidth = frame.width.toFloat()
      scaledHeight = scaledWidth / imageAspect
    } else {
      scaledHeight = frame.height.toFloat()
      scaledWidth = scaledHeight * imageAspect
    }

    return SizeF(scaledWidth, scaledHeight)
  }

  private fun coverAnchoredLeft(frame: View, image: Bitmap): Matrix {
    val scaled = sizeToCover(frame, image)
    val xform = Matrix()
    xform.postScale(-1f, 1f, image.width * 0.5f, 0f)
    xform.postScale(scaled.width / image.width.toFloat(), scaled.height / image.height.toFloat())
    return xform
  }

  private fun coverAnchoredRight(frame: View, image: Bitmap): Matrix {
    val scaled = sizeToCover(frame, image)
    val xform = Matrix()
    xform.postScale(-1f, 1f, image.width.toFloat() * 0.5f, 0f)
    xform.postScale(scaled.width / image.width.toFloat(), scaled.height / image.height.toFloat())
    return xform
  }

  private fun updatePreviewTransform(textureSize: Size) {
    val textureAspect = textureSize.height / textureSize.width.toFloat()

    val scaledWidth: Float
    val scaledHeight: Float

    if (previewView.width > previewView.height) {
      scaledHeight = previewView.width.toFloat()
      scaledWidth = previewView.width * textureAspect
    } else {
      scaledHeight = previewView.height.toFloat()
      scaledWidth = previewView.height * textureAspect
    }

    val centerX = previewView.width * 0.5f
    val centerY = previewView.height * 0.5f

    val xform = Matrix()
    xform.postRotate(-viewToRotation(previewView).toFloat(), centerX, centerY)
    xform.preScale(scaledWidth / previewView.width.toFloat(), scaledHeight / previewView.height.toFloat(), centerX, centerY)

    previewView.setTransform(xform)
  }

  private fun syncLeft() {
    if (leftBitmap == null) {
      leftResetButton.visibility = View.INVISIBLE
      leftImageView.visibility = View.INVISIBLE
      leftCaptureButton.visibility = View.VISIBLE
    } else {
      leftCaptureButton.visibility = View.INVISIBLE
      leftImageView.visibility = View.VISIBLE
      leftResetButton.visibility = View.VISIBLE

      leftBitmap?.let {
        leftImageView.setImageBitmap(it)
        leftImageView.imageMatrix = coverAnchoredRight(leftImageView, it)
      }
    }
  }

  private fun syncRight() {
    if (rightBitmap == null) {
      rightResetButton.visibility = View.INVISIBLE
      rightImageView.visibility = View.INVISIBLE
      rightCaptureButton.visibility = View.VISIBLE
    } else {
      rightCaptureButton.visibility = View.INVISIBLE
      rightImageView.visibility = View.VISIBLE
      rightResetButton.visibility = View.VISIBLE

      rightBitmap?.let {
        rightImageView.setImageBitmap(it)
        rightImageView.imageMatrix = coverAnchoredLeft(rightImageView, it)
      }
    }
  }

  // -----------------------------------------------------------------------------------------------------

  // Task
  private fun createPreviewUseCase(screenSize: Size, screenAspectRatio: Rational): Preview {
    val config = PreviewConfig.Builder().run {
      setLensFacing(CameraX.LensFacing.FRONT)
      setTargetResolution(screenSize)
      setTargetAspectRatio(screenAspectRatio)
      setTargetRotation(previewView.display.rotation)
      build()
    }
    val previewUseCase = Preview(config)

    previewUseCase.setOnPreviewOutputUpdateListener { previewOutput ->
      previewView.surfaceTexture = previewOutput.surfaceTexture
      updatePreviewTransform(previewOutput.textureSize)
    }

    return previewUseCase
  }

  // Task
  private fun initializeUseCases() {
    val metrics = DisplayMetrics().also {
      previewView.display.getRealMetrics(it)
    }
    val screenSize = Size(metrics.widthPixels, metrics.heightPixels)
    val screenAspectRatio = Rational(metrics.widthPixels, metrics.heightPixels)

    val previewUseCase = createPreviewUseCase(screenSize, screenAspectRatio)
    captureUseCase = createCaptureUseCase(screenSize, screenAspectRatio)

    CameraX.bindToLifecycle(this, previewUseCase, captureUseCase)
  }

  // Task
  private fun createCaptureUseCase(screenSize: Size, screenAspectRatio: Rational): ImageCapture {
    val config = ImageCaptureConfig.Builder().run {
      setLensFacing(CameraX.LensFacing.FRONT)
      setTargetAspectRatio(screenAspectRatio)
      setTargetRotation(previewView.display.rotation)
      build()
    }
    return ImageCapture(config)
  }

  // Task
  private fun viewToRotation(view: View) = when (view.display.rotation) {
    Surface.ROTATION_0 -> 0
    Surface.ROTATION_90 -> 90
    Surface.ROTATION_180 -> 180
    Surface.ROTATION_270 -> 270
    else -> 0
  }

  // Task
  private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
    val buffer: ByteBuffer = image.planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
  }

  // Task
  private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
    val xform = Matrix().apply {
      postRotate(degrees.toFloat(), bitmap.width / 2f, bitmap.height / 2f)
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, xform, false)
  }

  // Task
  private fun meldBitmaps(left: Bitmap, right: Bitmap): Bitmap {
    val bitmap = Bitmap.createBitmap(left.width + right.width, left.height, Bitmap.Config.ARGB_8888)

    val leftPixels = IntArray(left.width * left.height)
    left.getPixels(leftPixels, 0, left.width, 0, 0, left.width, left.height)
    bitmap.setPixels(leftPixels, 0, left.width, 0, 0, left.width, left.height)

    val rightPixels = IntArray(left.width * left.height)
    right.getPixels(rightPixels, 0, right.width, 0, 0, right.width, right.height)
    bitmap.setPixels(rightPixels, 0, right.width, left.width, 0, right.width, right.height)

    return bitmap
  }

  // Task
  private fun saveBitmap(bitmap: Bitmap) {
    val outFile = File(Environment.getExternalStorageDirectory(), "twoface.jpg")
    val outStream = FileOutputStream(outFile)
    bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outStream)
    outStream.close()
  }

  // Task
  private fun meldBitmapsAndSaveMaybe() {
    leftBitmap?.let { left ->
      rightBitmap?.let { right ->
        val bitmap = meldBitmaps(right, left)
        saveBitmap(bitmap)
      }
    }
  }

  // Task
  private fun takeLeftImage() {
    captureUseCase.takePicture(object : ImageCapture.OnImageCapturedListener() {
      override fun onCaptureSuccess(image: ImageProxy, rotationDegrees: Int) {
        var bitmap = imageProxyToBitmap(image)
        image.close()

        bitmap = rotateBitmap(bitmap, rotationDegrees)
        bitmap = Bitmap.createBitmap(bitmap, bitmap.width / 2, 0, bitmap.width / 2, bitmap.height)

        leftBitmap = bitmap
        meldBitmapsAndSaveMaybe()
        syncLeft()
      }
    })
  }

  // Task
  private fun takeRightImage() {
    captureUseCase.takePicture(object : ImageCapture.OnImageCapturedListener() {
      override fun onCaptureSuccess(image: ImageProxy, rotationDegrees: Int) {
        var bitmap = imageProxyToBitmap(image)
        image.close()

        bitmap = rotateBitmap(bitmap, rotationDegrees)
        bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width / 2, bitmap.height)

        rightBitmap = bitmap
        meldBitmapsAndSaveMaybe()
        syncRight()
      }
    })
  }
}
package net.touchcapture.qr.flutterqr

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.os.*
import android.os.Looper.getMainLooper
import android.util.Log
import androidx.core.content.ContextCompat.checkSelfPermission
import com.google.zxing.DecodeHintType
import com.journeyapps.barcodescanner.*
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlin.math.max
import kotlin.math.min

class ImageParse {
    private lateinit var channelResult: MethodChannel.Result
    private lateinit var resultHandler: Handler

    private val resultCallback = Handler.Callback {
        when (it.what) {
            R.id.zxing_decode_succeeded -> {
                val barcodeResult = it.obj
                barcodeResult?.let { barcode ->
                    val result = barcode as BarcodeResult
                    val code = mapOf(
                            "code" to result.text,
                            "type" to result.barcodeFormat.name,
                            "rawBytes" to result.rawBytes)
                    channelResult.success(code)
                }
                return@Callback true
            }
            R.id.zxing_decode_failed -> {
                channelResult.error("1001", "decode failed", "")
                return@Callback true
            }
            R.id.zxing_possible_result_points -> {
                val resultPoints = it.obj as List<*>
//                channelResult.error("1002", "decode failed", "resultPoints=$resultPoints")
                return@Callback true
            }
            else -> return@Callback false
        }
    }

    fun parseImage(call: MethodCall, result: MethodChannel.Result) {
        this.channelResult = result
        val path: String = call.arguments as String
        ///check permission
        if (Shared.activity?.let { checkSelfPermission(it, Manifest.permission.READ_EXTERNAL_STORAGE) }
                == PackageManager.PERMISSION_GRANTED) {
            resultHandler = Handler(getMainLooper(), resultCallback)
            val decoderThread = DecoderThread(resultHandler, path)
            decoderThread.start()

        } else {
            print("no permission to load image")
        }

    }


}

private class DecoderThread(val resultHandler: Handler, val imagePath: String) : Thread() {
    private lateinit var decoder: Decoder

    init {
        createDecoder()
    }

    override fun start() {
        super.start()
        loadImage()
    }

    private fun loadImage() {
        ///load bitmap
        val bitmap = BitmapFactory.decodeFile(imagePath)
        if (bitmap == null) {
            print("load image failed")
        } else {
            val byteArray = bitmapToYUV420SP(bitmap)
            decode(byteArray, bitmap.width, bitmap.height)
        }
    }


    private fun createDecoder() {
        val callback = DecoderResultPointCallback()
        val factory = DefaultDecoderFactory()
        val hints = hashMapOf<DecodeHintType, Any>()
        hints[DecodeHintType.NEED_RESULT_POINT_CALLBACK] = callback
        decoder = factory.createDecoder(hints)
        callback.decoder = decoder
    }

    private fun decode(data: ByteArray, width: Int, height: Int) {
        val sourceData = SourceData(data, width, height, ImageFormat.NV21, 0)
        sourceData.cropRect = Rect(0,0,width,height)
        val start = System.currentTimeMillis()

        val rawResult = decoder.decode(sourceData.createSource())
        if (rawResult != null) {
            val end = System.currentTimeMillis()
            Log.i("TAG==============", "Found barcode int ${end - start}ms")
            val barcodeResult = BarcodeResult(rawResult, sourceData)
            val message = Message.obtain(resultHandler, R.id.zxing_decode_succeeded, barcodeResult)
            val bundle = Bundle()
            message.data = bundle
            message.sendToTarget()
        } else {
            val message = Message.obtain(resultHandler, R.id.zxing_decode_failed)
            message.sendToTarget()
        }

        val resultPoints = BarcodeResult.transformResultPoints(decoder.possibleResultPoints, sourceData)
        val message = Message.obtain(resultHandler, R.id.zxing_possible_result_points, resultPoints)
        message.sendToTarget()

    }


    private fun bitmapToYUV420SP(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height

        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)
        val requiredWidth = if (width % 2 != 0) {
            width - 1
        } else {
            width
        }

        val requiredHeight = if (height % 2 != 0) {
            height - 1
        } else {
            height
        }
        val byteLength = (requiredWidth * requiredHeight * 3) / 2

        val yuvs = ByteArray(byteLength)
        argbToYUV420SP(yuvs, argb, width, height)
        bitmap.recycle()
        return yuvs
    }

    private fun argbToYUV420SP(byteArray: ByteArray, argb: IntArray, width: Int, height: Int) {

        val frameSize = width * height
        var y: Int
        var u: Int
        var v: Int
        var yIndex = 0
        var uvIndex = frameSize
        var r: Int
        var g: Int
        var b: Int
        var rgbIndex = 0
        for (j in 0 until height) {
            for (i in 0 until width) {
                /// and == &    shr == >>
                r = (argb[rgbIndex] and 0xff0000) shr 16
                g = (argb[rgbIndex] and 0xff00) shr 8
                b = (argb[rgbIndex] and 0xff)
                rgbIndex++


                /// RGB to YUV
                y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                y = max(0, min(y, 255))
                u = max(0, min(u, 255))
                v = max(0, min(v, 255))
                /// save to byteArray
                byteArray[yIndex++] = y.toByte()
                if ((j % 2 == 0) && (i % 2 == 0)) {
                    byteArray[uvIndex++] = v.toByte()
                    byteArray[uvIndex++] = u.toByte()
                }

            }
        }
    }


}
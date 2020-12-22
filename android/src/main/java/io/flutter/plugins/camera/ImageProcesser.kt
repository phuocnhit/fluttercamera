package online.khaothi.mobile

import android.util.Log
import android.view.Surface
import androidx.annotation.NonNull

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import org.opencv.android.OpenCVLoader
import org.opencv.aruco.Aruco
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.util.*
import kotlin.math.roundToInt

class ImageProcesser {
    var rvec: Mat? = null
    var tvec: Mat? = null
    var distortion_Coefficients: Mat? = null
    var camera_Matrix: Mat? = null
    constructor() {
        tvec = Mat()
        rvec = Mat()

        val distortion_Coefficients_Data = arrayOf(doubleArrayOf(0.02264976840468223), doubleArrayOf(0.906495922558728), doubleArrayOf(0.0), doubleArrayOf(0.0), doubleArrayOf(-3.78455027321318))

        val camera_Matrix_Data = arrayOf(doubleArrayOf(1060.734674682828, 0.0, 639.5), doubleArrayOf(0.0, 1060.734674682828, 359.5), doubleArrayOf(0.0, 0.0, 1.0))
        camera_Matrix = Mat(3, 3, CvType.CV_32F)

        for (row in 0..2) {
            for (col in 0..2) camera_Matrix!!.put(row, col, camera_Matrix_Data[row][col])
        }

        distortion_Coefficients = Mat(5, 1, CvType.CV_32F)
        for (row in 0..4) {
            for (col in 0..0) distortion_Coefficients!!.put(row, col, distortion_Coefficients_Data[row][col])
        }
    }
    fun rotate(src: Mat, angle: Double): Mat {
        val dst = Mat()
        if (angle == 180.0 || angle == -180.0) {
            Core.flip(src, dst, -1)
        } else if (angle == 90.0 || angle == -270.0) {
            Core.flip(src.t(), dst, 1)
        } else if (angle == 270.0 || angle == -90.0) {
            Core.flip(src.t(), dst, 0)
        }
        return dst
    }
    fun rotationMatrixToEulerAngles(R: Mat): DoubleArray? {
        val sy = Math.sqrt(R[0, 0][0] * R[0, 0][0] + R[1, 0][0] * R[1, 0][0])
        val singular = sy < 1e-6 // If
        var x = -1.0
        var y = -1.0
        var z = -1.0
        if (!singular) {
            x = Math.atan2(R[2, 1][0], R[2, 2][0])
            y = Math.atan2(-R[2, 0][0], sy)
            z = Math.atan2(R[1, 0][0], R[0, 0][0])
        } else {
            x = Math.atan2(-R[1, 2][0], R[1, 1][0])
            y = Math.atan2(-R[2, 0][0], sy)
            z = 0.0
        }
        //        Log.w("xyz", x+":"+y+":"+z);
        return doubleArrayOf(x, y, z)
    }
    fun eulerToDegree(euler: Double): Double {
        val pi = 22.0 / 7.0
        return euler / (2 * pi) * 360
    }

    fun degreeToChar(degree: Double): String? {
        if (degree >= -45 && degree <= 45) return "1"
        if (degree > 45 && degree <= 135) return "4"
        if (degree > 135 || degree <= -135) return "3"
        return if (degree >= -135 && degree <= -45) "2" else "0"
    }

    fun getYUV2Mat(img: Mat ,flip:Boolean ): List<Any?>? {
        val frame = rotate(img, 90.0)
        Imgproc.cvtColor(frame, frame, Imgproc.COLOR_RGBA2GRAY);
        if(flip){
            Core.flip(frame,frame, 1)
        }


        val responses: MutableList<Any?> = ArrayList()
        val dictionary = Aruco.getPredefinedDictionary(Aruco.DICT_5X5_250)
        val ids = Mat()
        val corners: List<Mat> = ArrayList()
        Aruco.detectMarkers(frame, dictionary, corners, ids)
        Aruco.drawDetectedMarkers(frame, corners)
        Aruco.estimatePoseSingleMarkers(corners, 0.1f, camera_Matrix, distortion_Coefficients, rvec, tvec)
        val count = ids.size().height.roundToInt()
        for (i in 0 until count) {
            val rmat = Mat()
            val jacobian = Mat()
            Calib3d.Rodrigues(rvec!!.row(i), rmat, jacobian)
            val t = rotationMatrixToEulerAngles(rmat)
            val answer = degreeToChar(eulerToDegree(t!![2]))
            val id = ids[i, 0][0].toInt()

            val x = (corners[i][0, 0][0] + corners[i][0, 2][0])/2
            val y = (corners[i][0, 0][1]  +corners[i][0, 2][1])/2

//            val json: String = gson.toJson(responseid)
//            Log.d("flutter","aaaaaaaaa total"+str)
            responses.add("{\"id\":"+id.toString()+",\"x\":"+x.toString()+",\"y\":"+y.toString()+",\"answer\":\""+answer+"\"}")
        }

        return responses;
    }
}
public class Response {
    var x = 0.0
    var y = 0.0
    var answer: String? = null
    var id = 0
}

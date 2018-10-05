package terry.com.tfcamerasupervisor;

import android.graphics.Bitmap;

public class JTUtil
{
    public static double df;//每次判断是否认真工作的得分值（0～1之间）
    //图像帧Bitmap每1000ms左右抓取一次
    //抓取完成后回调此方法
    public static void JT(final Bitmap bitmap)
    {
        //此处可以编写通过AI模块判断认真程度得分的代码
        //..........
        //计算出得分值后给df变量赋值即可显示到摄像头画面
        df=Math.random();
    }
}

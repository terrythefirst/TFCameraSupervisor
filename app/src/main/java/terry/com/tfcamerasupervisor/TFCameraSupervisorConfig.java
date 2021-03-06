package terry.com.tfcamerasupervisor;

import java.net.PortUnreachableException;

public class TFCameraSupervisorConfig {//项目参数配置静态类


    public static final int INPUT_WIDTH = 499;//CNN配置参数 传入网络的图片宽度 （拍照的宽度）
    public static final int INPUT_HEIGHT = 499;//CNN配置参数 传入网络的图片高度  （拍照的高度）

    public static final int IMAGE_MEAN = 117; //CNN配置参数  图片均值
    public static final float IMAGE_STD = 1; //CNN配置参数 图片标准差
    public static final String INPUT_NAME = "input"; //CNN配置参数 传入节点tensor名
    public static final String OUTPUT_NAME = "output";//CNN配置参数 输出节点tensor名

    public static final String MODEL_FILE = "file:///android_asset/tensorflow_inception_graph.pb";//CNN配置参数  模型文件路径
    public static final String LABEL_FILE =
            "file:///android_asset/imagenet_comp_graph_label_strings.txt";//CNN配置参数  标签文件路径

    public static final boolean SAVE_PREVIEW_BITMAP = true;
    public static final boolean MAINTAIN_ASPECT = true;//在根据INPUT_WIDTH(HEIGHT)缩放摄像机捕捉的画面时，是否保持比例

    public static int PREVIEW_MAX_HEIGHT = 1000;//最大高度预览尺寸，默认大于1000的第一个


    public static long lastSaveTimeStamp = -1;//时间戳 用于保存图片时间间隔的计算
    public static final int saveInterval = 1000;//保存图片间隔 单位：毫秒 ms

    public static final float countdown_time =1f;//minute  倒数计时时长 单位：分钟
    public static final int countdown_time_ms = (int)(countdown_time*60*1000);//倒数时长对应秒
}

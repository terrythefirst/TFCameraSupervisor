package terry.com.tfcamerasupervisor;

import java.net.PortUnreachableException;

public class TFCameraSupervisorConfig {
    // These are the settings for the original v1 Inception model. If you want to
    // use a model that's been produced from the TensorFlow for Poets codelab,
    // you'll need to set IMAGE_SIZE = 299, IMAGE_MEAN = 128, IMAGE_STD = 128,
    // INPUT_NAME = "Mul", and OUTPUT_NAME = "final_result".
    // You'll also need to update the MODEL_FILE and LABEL_FILE paths to point to
    // the ones you produced.
    //
    // To use v3 Inception model, strip the DecodeJpeg Op from your retrained
    // model first:
    //
    // python strip_unused.py \
    // --input_graph=<retrained-pb-file> \
    // --output_graph=<your-stripped-pb-file> \
    // --input_node_names="Mul" \
    // --output_node_names="final_result" \
    // --input_binary=true
    public static final int INPUT_SIZE = 499;
    public static final int IMAGE_MEAN = 117;
    public static final float IMAGE_STD = 1;
    public static final String INPUT_NAME = "input";
    public static final String OUTPUT_NAME = "output";

    public static final boolean MAINTAIN_ASPECT = true;

    public static int PREVIEW_MAX_HEIGHT = 1000;//最大高度预览尺寸，默认大于1000的第一个


    public static final String MODEL_FILE = "file:///android_asset/tensorflow_inception_graph.pb";
    public static final String LABEL_FILE =
            "file:///android_asset/imagenet_comp_graph_label_strings.txt";

    public static long lastSaveTimeStamp = -1;
    public static final int saveInterval = 1000;//ms
}

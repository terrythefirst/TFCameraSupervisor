package terry.com.tfcamerasupervisor;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.TextView;

public class NextActivity extends Activity {//空Activity  跳转到的Activity

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.next_activity_layout);
        Intent intent = getIntent();
        String msg = intent.getStringExtra("msg");
        TextView textView = findViewById(R.id.gathered_statics);
        textView.setText(msg);
    }
}

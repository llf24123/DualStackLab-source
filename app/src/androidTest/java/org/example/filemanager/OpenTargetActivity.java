package org.example.filemanager;

import android.app.Activity;
import android.os.Bundle;
import android.widget.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Runs in the test APK's UID to verify the FileProvider grant used by the real chooser. */
public class OpenTargetActivity extends Activity {
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);
        TextView data=new TextView(this),mime=new TextView(this);data.setTextSize(20);mime.setTextSize(20);
        try(InputStream input=getContentResolver().openInputStream(getIntent().getData())){data.setText("收到文件："+new String(input.readAllBytes(),StandardCharsets.UTF_8));}
        catch(Exception error){data.setText("读取失败："+error);}
        mime.setText("类型："+getIntent().getType());body.addView(data);body.addView(mime);setContentView(body);
    }
}

package tv.slink.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.activity.ComponentActivity;
import androidx.core.content.FileProvider;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class UpdateActivity extends ComponentActivity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final List<Button> settingsButtons=new ArrayList<>();
    private UpdateSettings settings;
    private UpdateClient client;
    private UpdateClient.Release release;
    private File downloaded;
    private TextView status,notes;
    private ProgressBar progress;
    private Button action,check,source,credential;
    private boolean destroyed,busy,awaitingPermission;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);settings=new UpdateSettings(this);
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(40),dp(24),dp(40),dp(24));body.setBackgroundColor(TvUi.BACKGROUND);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(body);setContentView(scroll);
        body.addView(TvUi.text(this,"Updates",26,Color.WHITE));body.addView(TvUi.text(this,"Installed · "+BuildConfig.VERSION_NAME,14,TvUi.MUTED));
        status=TvUi.text(this,"",17,Color.WHITE);status.setPadding(0,dp(16),0,dp(12));body.addView(status);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);progress.setVisibility(View.GONE);body.addView(progress,new LinearLayout.LayoutParams(-1,dp(8)));
        LinearLayout actions=new LinearLayout(this);body.addView(actions);
        check=TvUi.button(this,"Check now",v->check());actions.addView(check);
        action=TvUi.button(this,"Download update",v->{if(downloaded!=null)install();else download();});action.setEnabled(false);actions.addView(action);
        notes=TvUi.text(this,"",14,TvUi.MUTED);notes.setPadding(0,dp(12),0,dp(18));body.addView(notes);
        source=row(body,"Update source",settings.source().uri().toString(),v->editSource());
        credential=row(body,"GitHub credential",settings.hasToken()?"Replace":"Set",v->editToken());
        row(body,"Forget GitHub credential","Forget",v->{settings.forgetToken();credential.setText("Set");reset();status.setText("Credential removed.");});
        row(body,"Release channel",settings.previews()?"Preview":"Stable",v->{boolean value=!settings.previews();settings.preferences.edit().putBoolean("previews",value).apply();((Button)v).setText(value?"Preview":"Stable");reset();});
        row(body,"Check on app opening",settings.automatic()?"On":"Off",v->{boolean value=!settings.automatic();settings.preferences.edit().putBoolean("automatic",value).apply();((Button)v).setText(value?"On":"Off");});
        body.addView(TvUi.button(this,"Back",v->finish()));
        status.setText("Private GitHub repositories require a Contents: read credential.");check.requestFocus();
        if(getIntent().getBooleanExtra("check",false))check();
    }
    private int dp(int value){return TvUi.dp(this,value);}
    private Button row(LinearLayout body,String title,String value,View.OnClickListener click){
        LinearLayout row=new LinearLayout(this);row.setGravity(android.view.Gravity.CENTER_VERTICAL);row.setPadding(dp(14),dp(8),dp(8),dp(8));row.setBackground(TvUi.shape(this,0xFF182330,8));
        row.addView(TvUi.text(this,title,16,Color.WHITE),new LinearLayout.LayoutParams(0,-2,1));
        Button button=TvUi.button(this,value,click);button.setTextSize(14);button.setMaxWidth(dp(460));row.addView(button);settingsButtons.add(button);
        LinearLayout.LayoutParams bounds=new LinearLayout.LayoutParams(-1,-2);bounds.topMargin=dp(8);body.addView(row,bounds);return button;
    }
    private void reset(){release=null;downloaded=null;action.setText("Download update");action.setEnabled(false);notes.setText("");}
    private void busy(boolean value){busy=value;check.setEnabled(!value);for(Button button:settingsButtons)button.setEnabled(!value);action.setEnabled(!value&&release!=null);progress.setVisibility(value?View.VISIBLE:View.GONE);}
    private void check(){
        if(busy)return;reset();busy(true);progress.setIndeterminate(true);status.setText("Checking for updates…");
        worker.execute(()->{
            try{
                UpdateClient next=new UpdateClient(settings.source(),settings.token());
                UpdateClient.Release found=next.check(settings.previews(),BuildConfig.VERSION_CODE,android.os.Build.VERSION.SDK_INT);
                runOnUiThread(()->{if(destroyed)return;client=next;release=found;busy(false);
                    status.setText(found==null?"SlinkTV is up to date.":"Available · "+found.manifest().version());
                    if(found!=null){notes.setText(found.manifest().notes());action.requestFocus();}
                });
            }catch(Exception error){failure(error,"Could not check for updates.");}
        });
    }
    private void download(){
        if(busy||release==null)return;busy(true);progress.setIndeterminate(false);progress.setProgress(0);status.setText("Downloading update…");
        worker.execute(()->{
            try{
                File file=client.download(getApplicationContext(),release,percent->runOnUiThread(()->{if(!destroyed){progress.setProgress(percent);status.setText("Downloading update · "+percent+"%");}}));
                runOnUiThread(()->{if(destroyed)return;downloaded=file;busy(false);action.setText("Install update");status.setText("Download verified. Ready to install.");action.requestFocus();});
            }catch(Exception error){failure(error,"Could not download the update.");}
        });
    }
    private void failure(Exception error,String fallback){runOnUiThread(()->{if(destroyed)return;busy(false);status.setText(error instanceof java.io.IOException||error instanceof IllegalArgumentException?error.getMessage():fallback);});}
    private void install(){
        if(downloaded==null||release==null)return;
        if(!getPackageManager().canRequestPackageInstalls()){
            try{awaitingPermission=true;startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())));}
            catch(android.content.ActivityNotFoundException error){awaitingPermission=false;status.setText("Allow SlinkTV in Android Settings → Install unknown apps.");}
            return;
        }
        busy(true);progress.setIndeterminate(true);status.setText("Verifying update…");
        worker.execute(()->{
            try{
                UpdateClient.verifyFile(downloaded,release.manifest());UpdateClient.verifyPackage(this,downloaded,release.manifest());
                runOnUiThread(()->{
                    if(destroyed)return;busy(false);
                    try{
                        Uri uri=FileProvider.getUriForFile(this,getPackageName()+".updates",downloaded);
                        Intent installer=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        installer.setClipData(android.content.ClipData.newRawUri("SlinkTV update",uri));startActivity(installer);
                        status.setText("Complete installation in Android. If cancelled, choose Install update to retry.");
                    }catch(android.content.ActivityNotFoundException error){status.setText("Android's package installer is unavailable.");}
                });
            }catch(Exception error){failure(error,"Could not verify the update. Download again.");}
        });
    }

    @Override protected void onResume(){super.onResume();if(awaitingPermission){awaitingPermission=false;status.setText(getPackageManager().canRequestPackageInstalls()?"Installation allowed. Choose Install update.":"Installation permission was not granted.");}}
    private void editSource(){
        EditText input=new EditText(this);input.setSingleLine(true);input.setText(settings.source().uri().toString());input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);input.setPadding(dp(20),dp(12),dp(20),dp(12));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("GitHub repository or HTTPS update.json").setView(input).setPositiveButton("Save",null).setNegativeButton("Cancel",null).setNeutralButton("Default",(d,w)->{settings.source(UpdateSource.DEFAULT);source.setText(settings.source().uri().toString());credential.setText(settings.hasToken()?"Replace":"Set");reset();}).create();
        dialog.show();dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{settings.source(input.getText().toString());source.setText(settings.source().uri().toString());credential.setText(settings.hasToken()?"Replace":"Set");reset();dialog.dismiss();}catch(IllegalArgumentException error){input.setError(error.getMessage());}});
    }
    private void editToken(){
        EditText input=new EditText(this);input.setSingleLine(true);input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);input.setSaveEnabled(false);input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);input.setPadding(dp(20),dp(12),dp(20),dp(12));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("GitHub read credential").setMessage("Use a fine-grained token with Contents: read for this repository only. Stored encrypted on this TV.").setView(input).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        dialog.setOnDismissListener(d->{input.setText("");getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);});
        dialog.show();dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{settings.token(input.getText().toString().trim());credential.setText("Replace");reset();dialog.dismiss();status.setText("Credential saved.");}catch(Exception error){input.setError("Could not save. Check the token and GitHub source.");}});
    }
    @Override protected void onDestroy(){destroyed=true;worker.shutdownNow();super.onDestroy();}
}

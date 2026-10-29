package com.whale.pet;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Optional text-only AI chat. API credentials are stored only in private app preferences. */
public final class AiChatActivity extends Activity {
    private SharedPreferences prefs;
    private LinearLayout messages;
    private EditText input;
    private TextView status;
    private final JSONArray history = new JSONArray();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean busy;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("whale_ai_settings", MODE_PRIVATE);
        // Default to Groq's OpenAI-compatible API, which offers a rate-limited free tier.
        // Migrate the previous built-in OpenAI defaults and clear the old provider key so
        // users do not accidentally send an OpenAI key to a different provider.
        String savedBase = prefs.getString("base_url", "").trim().replaceAll("/+$", "");
        String savedModel = prefs.getString("model", "").trim();
        if (savedBase.isEmpty() || savedBase.equals("https://api.openai.com/v1")) {
            SharedPreferences.Editor edit = prefs.edit()
                .putString("base_url", "https://api.groq.com/openai/v1");
            if (savedModel.isEmpty() || savedModel.equals("gpt-4o-mini")) {
                edit.putString("model", "openai/gpt-oss-20b");
            }
            if (savedBase.equals("https://api.openai.com/v1")) {
                edit.remove("api_key");
            }
            edit.apply();
        } else if (savedModel.isEmpty()) {
            prefs.edit().putString("model", "openai/gpt-oss-20b").apply();
        }
        buildChat();
    }

    private int dp(float n) { return (int)(n * getResources().getDisplayMetrics().density + .5f); }
    private TextView text(String value, int size, int color) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); return v;
    }
    private void buildChat() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16),dp(12),dp(16),dp(12)); root.setBackgroundColor(Color.rgb(245,248,255)); setContentView(root);
        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=text("和泡泡鲸聊聊天",20,Color.rgb(35,61,102)); title.setTypeface(null,1);
        top.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button settings=new Button(this); settings.setText("AI 设置"); settings.setOnClickListener(v->showSettings()); top.addView(settings); root.addView(top);
        status=text("配置 API 后即可开始文字聊天。",12,Color.GRAY); root.addView(status);
        ScrollView scroll=new ScrollView(this); messages=new LinearLayout(this); messages.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(messages); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout composer=new LinearLayout(this); composer.setGravity(Gravity.CENTER_VERTICAL);
        input=new EditText(this); input.setHint("输入消息…"); input.setMaxLines(4);
        composer.addView(input,new LinearLayout.LayoutParams(0,-2,1));
        Button send=new Button(this); send.setText("发送"); send.setOnClickListener(v->sendMessage()); composer.addView(send);
        root.addView(composer);
        addMessage("assistant","你好呀！配置好 AI 服务后，我们就可以开始聊天啦。");
    }
    private void addMessage(String role,String content) {
        TextView bubble=text((role.equals("user")?"你：":"泡泡鲸：")+content,15,Color.rgb(35,48,70));
        bubble.setPadding(dp(12),dp(10),dp(12),dp(10)); bubble.setBackgroundColor(role.equals("user")?Color.rgb(220,235,255):Color.WHITE);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.setMargins(0,dp(6),0,dp(6)); messages.addView(bubble,lp);
    }
    private void sendMessage() {
        if(busy)return;
        String value=input.getText().toString().trim(); if(value.isEmpty())return;
        String key=prefs.getString("api_key","").trim();
        if(key.isEmpty()){ new AlertDialog.Builder(this).setMessage("请先在 AI 设置中填写 API Key。").setPositiveButton("去设置",(d,w)->showSettings()).setNegativeButton("取消",null).show(); return; }
        input.setText(""); addMessage("user",value);
        try { history.put(new JSONObject().put("role","user").put("content",value)); } catch(Exception ignored){}
        busy=true; status.setText("泡泡鲸正在思考…");
        worker.execute(()->{
            String answer; try { answer=requestAnswer(); } catch(Exception e) { answer="请求失败："+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()); }
            final String result=answer;
            runOnUiThread(()->{busy=false;status.setText("可以继续聊天。");addMessage("assistant",result);try{history.put(new JSONObject().put("role","assistant").put("content",result));}catch(Exception ignored){} });
        });
    }
    private String requestAnswer() throws Exception {
        String base=prefs.getString("base_url","").trim().replaceAll("/+$","");
        if(!base.startsWith("https://"))throw new IOException("API 地址必须使用 HTTPS。");
        URL url=new URL(base+"/chat/completions"); HttpURLConnection c=(HttpURLConnection)url.openConnection();
        c.setRequestMethod("POST"); c.setConnectTimeout(15000); c.setReadTimeout(45000); c.setDoOutput(true);
        c.setRequestProperty("Content-Type","application/json"); c.setRequestProperty("Authorization","Bearer "+prefs.getString("api_key","").trim());
        JSONArray messages=new JSONArray();
        messages.put(new JSONObject().put("role","system").put("content","你是泡泡鲸桌宠的陪伴角色。请用自然、温柔、可爱但不过度的中文口吻与用户聊天。你是原有泡泡鲸角色，不要假装拥有真实感知。"));
        for(int i=0;i<history.length();i++)messages.put(history.getJSONObject(i));
        JSONObject payload=new JSONObject().put("model",prefs.getString("model","gpt-4o-mini").trim()).put("messages",messages);
        try(OutputStream out=c.getOutputStream()){out.write(payload.toString().getBytes(StandardCharsets.UTF_8));}
        int code=c.getResponseCode(); InputStream stream=code>=200&&code<300?c.getInputStream():c.getErrorStream();
        StringBuilder body=new StringBuilder();try(BufferedReader reader=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8))){String line;while((line=reader.readLine())!=null)body.append(line);}
        c.disconnect(); JSONObject json=new JSONObject(body.toString());
        if(code<200||code>=300)throw new IOException("HTTP "+code+"："+json.optString("error",body.toString()));
        JSONArray choices=json.optJSONArray("choices");if(choices==null||choices.length()==0)throw new IOException("服务没有返回回复。");
        return choices.getJSONObject(0).getJSONObject("message").optString("content","（没有收到文字回复）");
    }
    private EditText field(LinearLayout box,String label,String value,boolean secret) {
        TextView caption=text(label,13,Color.DKGRAY);box.addView(caption);
        EditText e=new EditText(this);e.setSingleLine(true);e.setText(value);
        if(secret)e.setInputType(129);box.addView(e);return e;
    }
    private void showSettings() {
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(20),dp(8),dp(20),dp(8));
        EditText base=field(box,"API Base URL（Groq 免费额度 / OpenAI 兼容接口）",prefs.getString("base_url","https://api.groq.com/openai/v1"),false);
        EditText model=field(box,"模型名称",prefs.getString("model","openai/gpt-oss-20b"),false);
        EditText key=field(box,"Groq API Key（仅保存在本机应用私有设置）",prefs.getString("api_key",""),true);
        new AlertDialog.Builder(this).setTitle("AI 聊天设置").setView(box).setNegativeButton("取消",null).setPositiveButton("保存",(d,w)->{
            String url=base.getText().toString().trim().replaceAll("/+$","");
            if(!url.startsWith("https://")||model.getText().toString().trim().isEmpty()){status.setText("请填写 HTTPS 地址和模型名称。");return;}
            prefs.edit().putString("base_url",url).putString("model",model.getText().toString().trim()).putString("api_key",key.getText().toString().trim()).apply();
            status.setText("AI 设置已保存在本机。");
        }).show();
    }
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
}

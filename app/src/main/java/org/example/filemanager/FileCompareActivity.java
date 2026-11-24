package org.example.filemanager;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class FileCompareActivity extends LabActivity {
    static void choose(Activity activity, FileStore.Node left, FileStore.Node right) {
        if (left.directory && right.directory) { activity.startActivity(new Intent(activity, DirectoryCompareActivity.class).putExtra("left",left.key()).putExtra("right",right.key())); return; }
        List<String> labels=new ArrayList<>(List.of("文本对比")), modes=new ArrayList<>(List.of("text"));
        String a=left.name.toLowerCase(Locale.ROOT),b=right.name.toLowerCase(Locale.ROOT);
        if(a.matches(".*\\.(apk|zip|jar|apks)")&&b.matches(".*\\.(apk|zip|jar|apks)")){labels.add("APK / ZIP 对比");modes.add("zip");}
        if(a.matches(".*\\.(apk|dex)")&&b.matches(".*\\.(apk|dex)")){labels.add("DEX 对比");modes.add("dex");}
        if(a.matches(".*\\.(apk|arsc)")&&b.matches(".*\\.(apk|arsc)")){labels.add("ARSC 对比");modes.add("arsc");}
        if(a.endsWith(".apk")&&b.endsWith(".apk")){labels.add("AXML 对比");modes.add("axml");}
        MaterialDialogs.builder(activity).setTitle("文件对比").setItems(labels.toArray(String[]::new),(d,n)->activity.startActivity(new Intent(activity,FileCompareActivity.class)
                .putExtra("left",left.key()).putExtra("right",right.key()).putExtra("mode",modes.get(n)))).show();
    }
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled=new AtomicBoolean();
    private volatile boolean destroyed;
    private boolean busy,publishing,spaces,caseInsensitive,onlyChanges,detailSwapped;
    private FileComparison comparison;
    private String leftText,rightText,detailName,mode;
    private List<TextDiff.Row> rows=List.of(),visible=List.of();
    private ListView list;
    private TextView status,names;
    private TextAdapter adapter;
    private byte[] threeWayExport;
    private static final class UndoState { volatile TextMerge.Undo last; }
    private UndoState undoState;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);mode=getIntent().getStringExtra("mode");if(mode==null)mode="text";
        Object retained=getLastCustomNonConfigurationInstance();undoState=retained instanceof UndoState?(UndoState)retained:new UndoState();
        if(state!=null){spaces=state.getBoolean("spaces");caseInsensitive=state.getBoolean("case");onlyChanges=state.getBoolean("changes");}
        LinearLayout body=Ui.column(this),top=Ui.toolbar(this,"文件对比",this::back);
        top.addView(Ui.icon(this,R.drawable.ic_swap,"交换左右",Color.WHITE,this::swap));
        top.addView(Ui.icon(this,R.drawable.ic_up,"上一处差异",Color.WHITE,()->difference(false)));
        View next=Ui.icon(this,R.drawable.ic_up,"下一处差异",Color.WHITE,()->difference(true));next.setRotation(180);top.addView(next);
        top.addView(Ui.icon(this,R.drawable.ic_more,"对比菜单",Color.WHITE,this::menu));body.addView(top);
        names=Ui.single(this,"读取中…",12,Ui.MUTED);names.setPadding(Ui.dp(this,12),Ui.dp(this,6),Ui.dp(this,12),Ui.dp(this,6));body.addView(names);
        list=new ListView(this);list.setDivider(null);list.setContentDescription("文件差异列表");body.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        list.setOnItemClickListener((p,v,pos,id)->{
            if(busy)return;
            if(leftText!=null){TextDiff.Row row=visible.get(pos);LinearLayout content=Ui.column(this);
                TextView text=Ui.text(this,"左侧 "+row.leftLine()+"\n"+row.left()+"\n\n右侧 "+row.rightLine()+"\n"+row.right(),14,Ui.TEXT);text.setTextIsSelectable(true);text.setPadding(Ui.dp(this,20),Ui.dp(this,12),Ui.dp(this,20),Ui.dp(this,12));content.addView(text);ScrollView scroll=new ScrollView(this);scroll.addView(content);
                AlertDialog.Builder dialog=MaterialDialogs.builder(this).setTitle("行内容").setView(scroll).setPositiveButton("关闭",null);
                if(mode.equals("text")&&row.kind()!=TextDiff.Kind.SAME){
                    int selected=rows.indexOf(row);TextMerge.Block block=TextMerge.block(rows,selected);
                    dialog.setTitle("差异块 · "+(block.end()-block.first())+" 行");
                    StringBuilder preview=new StringBuilder();
                    for(int side=0;side<2;side++){
                        preview.append(side==0?"左侧\n":"\n右侧\n");int shown=0;
                        for(int i=block.first();i<block.end();i++){
                            TextDiff.Row part=rows.get(i);int line=side==0?part.leftLine():part.rightLine();if(line==0)continue;
                            String value=side==0?part.left():part.right();
                            if(++shown>100||preview.length()+value.length()>24000){preview.append("…更多内容见差异列表\n");break;}
                            preview.append(line).append("  ").append(value).append('\n');
                        }
                        if(shown==0)preview.append("（无内容）\n");
                    }
                    text.setText(preview);
                    dialog.setNeutralButton("左 → 右",(d,n)->prepareMerge(selected,true,false));
                    dialog.setNegativeButton("右 → 左",(d,n)->prepareMerge(selected,false,false));
                }
                dialog.show();
            }else openDetail(comparison.differences.get(pos));
        });
        status=Ui.text(this,"准备对比…",12,Ui.MUTED);status.setMinHeight(Ui.dp(this,48));status.setPadding(Ui.dp(this,12),0,Ui.dp(this,12),0);body.addView(status);Ui.install(this,body);load();
    }
    private interface Job{void run()throws Exception;}
    private void run(Job job,Runnable done){run("正在对比… · 菜单可停止",job,done);}
    private void run(String message,Job job,Runnable done){
        if(busy)return;busy=true;publishing=message.contains("保存")||message.contains("撤销");cancelled.set(false);status.setText(message);
        worker.execute(()->{Exception failure=null;try{job.run();}catch(Exception e){failure=e;}Exception error=failure;
            runOnUiThread(()->{if(destroyed)return;busy=false;publishing=false;if(error!=null){status.setText(error.getMessage());if(!(error instanceof InterruptedIOException))Tasks.showError(this,error);}else done.run();});});
    }
    private void load(){
        leftText=null;rightText=null;detailName=null;detailSwapped=false;
        run(()->{
            if(comparison!=null)comparison.close();FileStore store=new FileStore(this);
            comparison=new FileComparison(this,store.fromKey(getIntent().getStringExtra("left")),store.fromKey(getIntent().getStringExtra("right")),cancelled::get);
            comparison.load(mode);
            if(mode.equals("text")){leftText=comparison.leftText;rightText=comparison.rightText;rows=TextDiff.compare(leftText,rightText,spaces,caseInsensitive,cancelled::get);}
        },()->{names.setText(comparison.sources.get(0).name+"  ↔  "+comparison.sources.get(1).name);show();});
    }
    private void show(){
        if(leftText!=null){visible=onlyChanges?rows.stream().filter(r->r.kind()!=TextDiff.Kind.SAME).toList():rows;adapter=new TextAdapter();list.setAdapter(adapter);
            long changed=rows.stream().filter(r->r.kind()!=TextDiff.Kind.SAME).count();status.setText(changed==0?"内容一致":"差异 "+changed+" 行"+(mode.equals("text")?" · 点按差异块可合并":" · 左侧为原文件，右侧为新文件"));}
        else{list.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,comparison.differences.stream().map(r->r.label()+"  "+r.name()).toArray(String[]::new)));status.setText("已比较 "+comparison.compared+" 项 · 差异 "+comparison.differences.size()+" 项");}
        Ui.fadeIn(list);
    }
    private void openDetail(FileComparison.Difference item){
        detailSwapped=false;
        run(()->{leftText=comparison.content(item.left());rightText=comparison.content(item.right());rows=TextDiff.compare(leftText,rightText,spaces,caseInsensitive,cancelled::get);detailName=item.name();},()->{names.setText(detailName);show();});
    }
    private void difference(boolean forward){
        if(busy||leftText==null||visible.isEmpty())return;int at=list.getFirstVisiblePosition(),step=forward?1:-1;
        for(int count=1;count<=visible.size();count++){int i=Math.floorMod(at+count*step,visible.size());if(visible.get(i).kind()!=TextDiff.Kind.SAME){list.setSelection(i);return;}}
    }
    private void swap(){
        if(busy)return;
        if(detailName!=null){String old=leftText;leftText=rightText;rightText=old;detailSwapped=!detailSwapped;names.setText(detailName+(detailSwapped?" · 左右已交换":""));recompare();return;}
        String old=getIntent().getStringExtra("left");getIntent().putExtra("left",getIntent().getStringExtra("right")).putExtra("right",old);load();
    }
    private void recompare(){run(()->rows=TextDiff.compare(leftText,rightText,spaces,caseInsensitive,cancelled::get),this::show);}
    private void prepareMerge(int selected,boolean toRight,boolean all){
        if(busy||comparison==null||!mode.equals("text")||leftText==null||rightText==null
                ||comparison.textSnapshots[0]==null||comparison.textSnapshots[1]==null)return;
        FileComparison current=comparison;int source=toRight?0:1,target=1-source;
        String[] merged={null};
        run("正在准备合并…",()->{
            TextDiff.check(cancelled::get);
            merged[0]=all?TextMerge.applyAll(toRight?leftText:rightText,toRight?rightText:leftText)
                    :TextMerge.apply(leftText,rightText,rows,selected,toRight);
            // Validate representability before showing a save action.
            TextCodec.Decoded codec=current.textSnapshots[target].decoded();
            TextCodec.encode(merged[0],codec.charset(),codec.bom());
        },()->{
            show();
            if(merged[0].equals(current.textSnapshots[target].text())){Tasks.toast(this,"目标内容没有变化");return;}
            String direction=toRight?"左侧 → 右侧":"右侧 → 左侧";
            TextMerge.Snapshot baseline=current.textSnapshots[target];
            String note=(all?"使用全部内容":"使用这处差异块")+"："+direction+"\n\n保存到："+current.sources.get(target).path()
                    +"\n编码："+baseline.decoded().charset().name()+(baseline.decoded().bom().length>0?" · 保留 BOM":"")
                    +"\n保留目标换行格式；保存后可从菜单撤销本次合并。";
            MaterialDialogs.builder(this).setTitle("确认合并并保存").setMessage(note).setNegativeButton("取消",null)
                    .setPositiveButton("保存合并",(d,n)->{
                        if(comparison!=current||busy)return;
                        run("正在保存合并…",()->{
                            current.store.checkRevision(current.sources.get(source),current.textSnapshots[source].revision());
                            undoState.last=TextMerge.save(current.store,current.sources.get(target),baseline,merged[0],cancelled::get);
                        },this::load);
                    }).show();
        });
    }
    private void undoMerge(){
        TextMerge.Undo undo=undoState.last;if(undo==null||busy)return;
        MaterialDialogs.builder(this).setTitle("撤销上次合并？").setMessage("将恢复合并前的完整文件。若文件在外部被修改，将停止恢复。")
                .setNegativeButton("取消",null).setPositiveButton("撤销合并",(d,n)->run("正在撤销合并…",()->{
                    TextMerge.undo(new FileStore(this),undo,cancelled::get);undoState.last=null;
                },this::load)).show();
    }
    private void menu(){
        if(busy){if(publishing)Tasks.toast(this,"正在保存，请稍候");else MaterialDialogs.builder(this).setItems(new String[]{"停止对比"},(d,n)->cancelled.set(true)).show();return;}
        if(comparison==null)return;
        List<String> options=new ArrayList<>();List<Runnable> actions=new ArrayList<>();
        options.add("重新对比");actions.add(this::load);
        options.add("导出差异");actions.add(()->startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"DualStackLab-diff.txt"),20));
        if(leftText!=null){
            options.add(spaces?"保留空白差异":"忽略空白差异");actions.add(()->{spaces=!spaces;recompare();});
            options.add(caseInsensitive?"区分大小写":"忽略大小写");actions.add(()->{caseInsensitive=!caseInsensitive;recompare();});
            options.add(onlyChanges?"显示全部行":"只显示差异");actions.add(()->{onlyChanges=!onlyChanges;show();});
        }
        if(mode.equals("text")){
            if(leftText!=null&&rightText!=null){
                options.add("全部左侧内容 → 右侧");actions.add(()->prepareMerge(-1,true,true));
                options.add("全部右侧内容 → 左侧");actions.add(()->prepareMerge(-1,false,true));
                options.add("选择基准文件进行三方合并");
                actions.add(() -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                        .addCategory(Intent.CATEGORY_OPENABLE), 22));
            }
            if(undoState.last!=null){options.add("撤销上次合并");actions.add(this::undoMerge);}
            for(int i=0;i<2;i++){int side=i;options.add(i==0?"编辑左侧文件":"编辑右侧文件");actions.add(()->{
                FileStore.Node source=comparison.sources.get(side);Intent intent=new Intent(this,EditorActivity.class);source.put(intent);startActivityForResult(intent,21);
            });}
        }
        MaterialDialogs.builder(this).setItems(options.toArray(String[]::new),(d,n)->actions.get(n).run()).show();
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(request==21){load();return;}
        if (request == 22 && result == RESULT_OK && data != null && data.getData() != null) {
            prepareThreeWay(data.getData());
            return;
        }
        if (request == 23 && result == RESULT_OK && data != null && data.getData() != null && threeWayExport != null) {
            byte[] output = threeWayExport;
            threeWayExport = null;
            run("正在导出合并结果…", () -> {
                try (OutputStream stream = getContentResolver().openOutputStream(data.getData(), "wt")) {
                    if (stream == null) throw new IOException("无法写入合并结果");
                    stream.write(output);
                }
            }, () -> status.setText("三方合并结果已导出"));
            return;
        }
        if(request!=20||result!=RESULT_OK||data==null||data.getData()==null)return;
        var destination=data.getData();run(()->{try(OutputStream stream=getContentResolver().openOutputStream(destination,"wt")){
            if(stream==null)throw new IOException("无法写入报告");Writer out=new OutputStreamWriter(stream,StandardCharsets.UTF_8);
            out.write("左侧："+comparison.sources.get(detailSwapped?1:0).path()+"\n右侧："+comparison.sources.get(detailSwapped?0:1).path()+"\n");
            if(detailName!=null)out.write("条目："+detailName+"\n");
            if(leftText!=null){for(TextDiff.Row row:rows){TextDiff.check(cancelled::get);if(row.kind()==TextDiff.Kind.SAME)continue;if(row.leftLine()>0)out.write("- "+row.leftLine()+" "+row.left()+"\n");if(row.rightLine()>0)out.write("+ "+row.rightLine()+" "+row.right()+"\n");}}
            else for(FileComparison.Difference row:comparison.differences){TextDiff.check(cancelled::get);out.write(row.label()+"\t"+row.name()+"\n");}out.flush();
        }},()->status.setText("差异报告已导出"));
    }
    private void prepareThreeWay(android.net.Uri uri) {
        if (busy || leftText == null || rightText == null || !mode.equals("text")) return;
        String left = leftText, right = rightText;
        ThreeWayMerge.Plan[] prepared = {null};
        run("正在计算三方差异…", () -> {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException("无法读取基准文件");
                byte[] base = FileStore.readBytes(input, FileComparison.TEXT_LIMIT + 1);
                if (base.length > FileComparison.TEXT_LIMIT) throw new IOException("基准文本超过 4 MiB");
                prepared[0] = ThreeWayMerge.plan(TextCodec.decode(base, null).text(), left, right, cancelled::get);
            }
        }, () -> resolveThreeWay(prepared[0], prepared[0].conflicts(), new ArrayList<>()));
    }
    private void resolveThreeWay(ThreeWayMerge.Plan plan, List<ThreeWayMerge.Region> conflicts, List<Integer> choices) {
        if (choices.size() == conflicts.size()) {
            MaterialDialogs.builder(this).setTitle("三方合并结果")
                    .setMessage("已处理 " + choices.size() + " 处冲突。导出为 UTF-8 文本文件。")
                    .setNegativeButton("取消", null).setPositiveButton("导出", (d, w) -> run("正在准备导出…", () -> {
                        byte[] bytes = plan.render(choices).getBytes(StandardCharsets.UTF_8);
                        if (bytes.length > FileComparison.TEXT_LIMIT * 2) throw new IOException("合并结果超过 8 MiB");
                        threeWayExport = bytes;
                    }, () -> startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain")
                            .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "merged.txt"), 23))).show();
            return;
        }
        ThreeWayMerge.Region conflict = conflicts.get(choices.size());
        String message = "基准第 " + conflict.baseLine() + " 行附近\n\n左侧\n" + mergeExcerpt(conflict.left())
                + "\n\n右侧\n" + mergeExcerpt(conflict.right()) + "\n\n基准\n" + mergeExcerpt(conflict.base());
        MaterialDialogs.builder(this).setTitle("冲突 " + (choices.size() + 1) + " / " + conflicts.size()).setMessage(message)
                .setPositiveButton("用左侧", (d, w) -> { choices.add(0); resolveThreeWay(plan, conflicts, choices); })
                .setNegativeButton("用右侧", (d, w) -> { choices.add(1); resolveThreeWay(plan, conflicts, choices); })
                .setNeutralButton("保留基准", (d, w) -> { choices.add(2); resolveThreeWay(plan, conflicts, choices); }).show();
    }
    private static String mergeExcerpt(List<String> lines) {
        String text = String.join("\n", lines.stream().limit(18).toList());
        if (text.length() > 1800) return text.substring(0, 1800) + "…";
        return lines.size() > 18 ? text + "\n…" : text.isEmpty() ? "（空）" : text;
    }
    private void back(){if(publishing){Tasks.toast(this,"正在保存，请稍候");return;}if(detailName!=null&&!busy){detailName=null;detailSwapped=false;leftText=null;rightText=null;names.setText(comparison.sources.get(0).name+"  ↔  "+comparison.sources.get(1).name);show();}else finish();}
    @Override protected void handleBack(){back();}
    @Override public Object onRetainCustomNonConfigurationInstance(){return undoState;}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putBoolean("spaces",spaces);state.putBoolean("case",caseInsensitive);state.putBoolean("changes",onlyChanges);}
    @Override protected void onPaletteChanged(){if(adapter!=null)adapter.notifyDataSetChanged();}
    @Override protected void onDestroy(){destroyed=true;cancelled.set(true);worker.execute(()->{if(comparison!=null)try{comparison.close();}catch(IOException ignored){}});worker.shutdown();super.onDestroy();}
    private final class TextAdapter extends BaseAdapter{
        public int getCount(){return visible.size();}public Object getItem(int i){return visible.get(i);}public long getItemId(int i){return i;}
        public View getView(int i,View convert,ViewGroup parent){
            LinearLayout row=convert instanceof LinearLayout?(LinearLayout)convert:Ui.row(FileCompareActivity.this);
            if(row.getChildCount()==0){for(int j=0;j<2;j++){TextView text=Ui.text(FileCompareActivity.this,"",12,Ui.TEXT);text.setTypeface(Typeface.MONOSPACE);text.setMinHeight(Ui.dp(FileCompareActivity.this,48));text.setPadding(Ui.dp(FileCompareActivity.this,4),Ui.dp(FileCompareActivity.this,4),Ui.dp(FileCompareActivity.this,4),Ui.dp(FileCompareActivity.this,4));text.setMaxLines(4);text.setEllipsize(android.text.TextUtils.TruncateAt.END);row.addView(text,new LinearLayout.LayoutParams(0,-1,1));}}
            TextDiff.Row value=visible.get(i);for(int side=0;side<2;side++){
                TextView text=(TextView)row.getChildAt(side);int number=side==0?value.leftLine():value.rightLine();String content=side==0?value.left():value.right();
                text.setText(number==0?"":number+"  "+(content.length()>2048?content.substring(0,2048)+"…":content));text.setTextColor(Ui.TEXT);
                text.setBackgroundColor(ToolAppearance.diffCell(value.kind(),side==0));
            }return row;
        }
    }
}

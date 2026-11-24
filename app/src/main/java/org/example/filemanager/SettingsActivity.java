package org.example.filemanager;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.os.Bundle;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.function.Supplier;

/** Category navigation with global search and independently restored scroll positions. */
public class SettingsActivity extends LabActivity {
    static final String EXTRA_EDITOR_ONLY = "editor-settings";
    static final int REQUEST_DIRECTORY = 11;
    private boolean editorOnly;
    private LinearLayout body, toolbar, searchBar;
    private LinearLayout content;
    private ScrollView scroll;
    private String query = "";
    private String selectedGroup = "";
    private EditText search;
    private TextView toolbarTitle;
    private boolean changingSearch;
    private final Runnable renderSearch = () -> renderAt(0, 0);
    private int renderGeneration;
    private final Map<String, Integer> positions = new HashMap<>();
    private final List<Runnable> refreshers = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private record Row(String group, String title, Supplier<String> summary, String key, boolean fallback, Runnable action) { }
    private record Category(String key, String title, String detail, int icon) { }
    private static final Category[] CATEGORIES = {
            new Category("外观", "外观与显示", "主题配色、文件列表与动画", R.drawable.ic_theme),
            new Category("启动", "启动与权限", "首页路径、Root 与 Shizuku", R.drawable.ic_home),
            new Category("文件管理", "文件管理", "打开方式、菜单顺序与保存", R.drawable.ic_folder),
            new Category("书签与底部工具栏", "书签与手势", "书签位置、排列与底栏手势", R.drawable.ic_bookmark),
            new Category("文本编辑器", "文本编辑器", "排版、输入、语法与文件", R.drawable.ic_edit),
            new Category("终端", "终端", "字体大小与屏幕亮起", R.drawable.ic_terminal),
            new Category("备份", "备份与迁移", "导入、导出书签和偏好", R.drawable.ic_save)
    };
    private SharedPreferences prefs() { return FileManagerSettings.prefs(this); }
    private SharedPreferences main() { return getSharedPreferences("MainActivity", 0); }
    private SharedPreferences editor() { return getSharedPreferences("editor-display", 0); }
    static Intent editorIntent(Context context) { return new Intent(context,SettingsActivity.class).putExtra(EXTRA_EDITOR_ONLY,true); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        editorOnly=state!=null?state.getBoolean(EXTRA_EDITOR_ONLY):getIntent().getBooleanExtra(EXTRA_EDITOR_ONLY,false);
        body = Ui.column(this);
        toolbar = Ui.toolbar(this, "设置", this::handleBack);
        toolbarTitle = (TextView) toolbar.getChildAt(1);
        toolbar.addView(Ui.icon(this, R.drawable.ic_search, "搜索设置", Ui.TEXT, () -> showSearch(search.getVisibility() != View.VISIBLE)),
                new LinearLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,52)));
        body.addView(toolbar);
        searchBar = Ui.row(this);
        searchBar.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 12));
        search = Ui.input(this, editorOnly?"搜索编辑器设置":"搜索设置"); search.setContentDescription("搜索设置输入框");
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        search.setOnEditorActionListener((v, action, event) -> { if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(); return true; } return false; });
        searchBar.addView(search, new LinearLayout.LayoutParams(-1, -2));
        body.addView(searchBar);
        scroll = new ScrollView(this);
        scroll.setFillViewport(true); scroll.setClipToPadding(false);
        scroll.setContentDescription("设置内容");
        content = Ui.column(this);
        content.setPadding(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 24));
        scroll.addView(content); body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        Ui.install(this, body); build();
        boolean searching = false;
        if (state != null) {
            selectedGroup = state.getString("category", "");
            query = state.getString("query", "");
            searching = state.getBoolean("search-open");
            Bundle savedPositions = state.getBundle("positions");
            if (savedPositions != null) for (String key : savedPositions.keySet()) positions.put(key, savedPositions.getInt(key));
        } else if (getIntent().hasExtra("query")) {
            query = Objects.toString(getIntent().getStringExtra("query"), ""); searching = true;
        }
        if (!selectedGroup.isEmpty() && category(selectedGroup) == null) selectedGroup = "";
        if(editorOnly)selectedGroup="文本编辑器";
        else if(selectedGroup.equals("文本编辑器"))selectedGroup="";
        search.setText(query);
        setSearching(searching);
        styleChrome(); renderAt(positions.getOrDefault(positionKey(), 0), 0);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int a,int c,int f){}
            public void onTextChanged(CharSequence s,int a,int b,int c){
                if (changingSearch) return;
                query=s.toString();
                if(!isSearching()&&!query.isEmpty()){rememberPosition();setSearching(true);}
                content.removeCallbacks(renderSearch);
                content.postDelayed(renderSearch, 90);
            }
            public void afterTextChanged(Editable e){}
        });
    }
    private void showSearch(boolean visible){
        if (visible == isSearching()) return;
        rememberPosition(); setSearching(visible);
        if(visible){
            renderAt(0, 1); search.requestFocus();
            ((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(search,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        } else {
            hideKeyboard(); changingSearch=true; search.setText(""); changingSearch=false; query="";
            renderAt(positions.getOrDefault(positionKey(), 0), -1);
        }
    }
    private boolean isSearching() { return search.getVisibility() == View.VISIBLE; }
    private void setSearching(boolean searching) {
        searchBar.setVisibility(searching ? View.VISIBLE : View.GONE);
        search.setVisibility(searching ? View.VISIBLE : View.GONE);
    }
    private String positionKey() { return isSearching() ? "search" : selectedGroup.isEmpty() ? "home" : selectedGroup; }
    private void rememberPosition() { positions.put(positionKey(), scroll.getScrollY()); }
    private Category category(String key) { for (Category category : CATEGORIES) if (category.key.equals(key)) return category; return null; }
    private void openCategory(Category category) {
        rememberPosition(); selectedGroup=category.key;
        renderAt(positions.getOrDefault(positionKey(), 0), 1);
    }
    @Override protected void onSaveInstanceState(Bundle state){
        super.onSaveInstanceState(state); rememberPosition();
        state.putBoolean(EXTRA_EDITOR_ONLY,editorOnly);
        state.putString("query",query);state.putBoolean("search-open",isSearching());state.putString("category",selectedGroup);
        Bundle savedPositions=new Bundle();for(var entry:positions.entrySet())savedPositions.putInt(entry.getKey(),entry.getValue());state.putBundle("positions",savedPositions);
    }
    @Override protected void handleBack(){
        if(isSearching())showSearch(false);
        else if(editorOnly)super.handleBack();
        else if(!selectedGroup.isEmpty()){rememberPosition();selectedGroup="";renderAt(positions.getOrDefault("home",0),-1);}
        else super.handleBack();
    }
    private void add(String group, String title, Supplier<String> summary, Runnable action) { rows.add(new Row(group,title,summary,null,false,action)); }
    private void toggle(String group,String title,String detail,String key,boolean fallback) { rows.add(new Row(group,title,()->detail,key,fallback,null)); }
    private void build() {
        toggle("启动", "请求 Root 权限", "启动时自动获取 Root 权限", "root", true);
        add("启动", "自定义 su 命令", () -> prefs().getString("su","su"), () -> FileManagerSettings.rootCommand(this));
        toggle("启动", "自动使用已授权的 Shizuku", "访问 Android/data 时使用已授予的权限", "auto-shizuku", true);
        add("启动", "系统目录授权", () -> "使用系统选择器授权目录，并保存到书签", this::authorizeDirectory);
        for (String side : new String[]{"left","right"}) add("启动", "启动路径 · " + (side.equals("left")?"左窗口":"右窗口"),
                () -> prefs().getString("startup-"+side,"home").equals("last") ? "上次路径" : "首页",
                () -> choice("启动路径",new String[]{"首页","上次路径"},prefs().getString("startup-"+side,"home").equals("last")?1:0,
                        n -> prefs().edit().putString("startup-"+side,n==0?"home":"last").apply()));
        add("启动","恢复默认首页",()->"左右窗口回到内部存储",()->MaterialDialogs.builder(this).setTitle("恢复默认首页？")
                .setNegativeButton("取消",null).setPositiveButton("恢复",(d,n)->{main().edit().remove("home-left").remove("home-right").apply();render();}).show());
        add("外观","主题材质",()->ThemeMaterial.current(this).title+" · "+ThemePicker.modeLabel(Ui.themeMode(this)),()->Ui.chooseTheme(this));
        add("外观","文件图标样式",()->{
            ThemeMaterial m=ThemeMaterial.current(this);
            return m.title+" · "+(m==ThemeMaterial.CLASSIC?"固定使用原版图标":IconStyle.forMaterial(this,m).title);
        },()->ThemePicker.showIcons(this));
        String[] accents={"跟随材质","墨绿","蓝色","紫色","茶色","石墨"};
        add("外观","主题颜色",()->accents[Ui.accentIndex+1],()->choice("主题颜色",accents,Ui.accentIndex+1,n->{
            var edit=prefs().edit();if(n==0)edit.remove("accent");else edit.putInt("accent",n-1);edit.apply();applyAppearance();}));
        add("外观","列表密度",()->main().getBoolean("comfortable",false)?"舒适":"紧凑",()->choice("列表密度",new String[]{"紧凑","舒适"},main().getBoolean("comfortable",false)?1:0,n->main().edit().putBoolean("comfortable",n==1).apply()));
        add("外观","文件名最大行数",()->FileListDisplay.lines(this)+" 行",()->choice("文件名最大行数",new String[]{"1 行","2 行","3 行","4 行","5 行","6 行","7 行","8 行"},FileListDisplay.lines(this)-1,n->prefs().edit().putInt("name-lines",n+1).apply()));
        toggle("外观","显示时间秒数","文件列表时间精确到秒","seconds",false);
        toggle("外观","简化年份","年份显示为两位数字","short-year",true);
        add("外观","日期时间格式",()->prefs().getString("date-format","yyyy-MM-dd HH:mm:ss"),this::dateFormat);
        toggle("外观","应用目录图标","在 Android/data 等目录显示应用角标","app-badges",true);
        toggle("外观","文件列表显示权限","非共享存储目录可显示权限和大小","list-permissions",false);
        toggle("外观","界面动画","目录切换、选中反馈和面板统一过渡","animations",true);
        add("文件管理","隐藏文件",()->main().getBoolean("showHidden",true)?"显示":"隐藏",()->choice("隐藏文件",new String[]{"显示","隐藏"},main().getBoolean("showHidden",true)?0:1,n->main().edit().putBoolean("showHidden",n==0).apply()));
        add("文件管理","文件操作菜单排序",()->"长按拖动，点按也可调整位置",()->FileMenuOrder.show(this));
        add("文件管理","默认打开方式",()->"按扩展名管理默认应用",()->startActivity(new Intent(this,OpenMethodsActivity.class)));
        add("文件管理","内置打开方式排序",()->"调整文本、图片、Hex 等工具顺序",()->OpenMethods.reorder(this));
        add("文件管理","公共数据目录",()->FileManagerSettings.workspace(this).getPath(),()->FileManagerSettings.chooseWorkspace(this));
        toggle("文本编辑器","编辑时生成备份文件","保存前在同目录生成 .bak；包内编辑备份整个压缩包，重名自动另存","editor-backup",false);
        toggle("文件管理","保留文件时间","复制和解压时保留原修改时间；目标存储需支持修改时间属性","preserve-time",true);
        toggle("书签与底部工具栏","在侧栏显示书签","位置与工具列表中显示保存的书签","sidebar-bookmarks",false);
        toggle("书签与底部工具栏","新书签加到最前","关闭后添加到书签列表末尾","bookmarks-top",false);
        toggle("书签与底部工具栏","按上滑位置选择窗口","从左半边上滑打开左窗口书签，从右半边上滑打开右窗口书签","gesture-side",false);
        add("书签与底部工具栏","书签上滑手势",()->"调整底部工具栏下边距",()->FileManagerSettings.gesture(this,this::render));
        add("文本编辑器","字体大小",()->Math.round(editor().getFloat("textSize",13))+" sp",()->number("字体大小",9,32,Math.round(editor().getFloat("textSize",13)),n->editor().edit().putFloat("textSize",n).apply()));
        add("文本编辑器","Tab 宽度",()->editor().getInt("tabWidth",4)+" 个空格",()->choice("Tab 宽度",new String[]{"2 个空格","4 个空格","8 个空格"},editor().getInt("tabWidth",4)==2?0:editor().getInt("tabWidth",4)==8?2:1,n->editor().edit().putInt("tabWidth",new int[]{2,4,8}[n]).apply()));
        editorToggle("自动换行","wordwrap",false); editorToggle("等宽字体","monospace",false); editorToggle("显示行号","lineNumbers",true);
        editorToggle("显示特殊字符","special",false); editorToggle("流畅模式","smooth",false);
        editorToggle("显示 Unicode 特殊字符","unicode-special",false);
        editorToggle("自动换行不断词","word-boundaries",true); editorToggle("固定行号","pin-lines",true);
        editorToggle("显示自动换行箭头","wrap-arrows",false); editorToggle("显示空白符号","whitespace",false);
        editorToggle("自动缩进","auto-indent",true); editorToggle("使用制表符缩进","tabs",false);
        editorToggle("显示缩进参考线","indent-guides",true); editorToggle("括号自动补全","bracket-completion",true);
        editorToggle("语法高亮","highlight",true); editorToggle("启用放大镜","magnifier",true);
        editorToggle("双指缩放","pinch-zoom",true); editorToggle("快捷符号栏","symbol-bar",true);
        editorToggle("回车应用补全","completion-enter",false);
        add("文本编辑器","补全窗口最大高度",()->editor().getInt("completion-rows",3)+" 行",()->number("补全窗口最大高度",2,10,editor().getInt("completion-rows",3),n->editor().edit().putInt("completion-rows",n).apply()));
        add("文本编辑器","编辑快捷符号栏",()->"自定义符号和短语顺序",()->EditorPreferences.editSymbols(this));
        add("文本编辑器","编辑器菜单排序",()->"长按拖动，点按调整",()->EditorPreferences.reorder(this));
        add("文本编辑器","自定义行距",()->new String[]{"1 倍","1.25 倍","1.5 倍","2 倍"}[Math.max(0,Math.min(3,editor().getInt("line-spacing",0)))],
                ()->choice("自定义行距",new String[]{"1 倍","1.25 倍","1.5 倍","2 倍"},editor().getInt("line-spacing",0),n->editor().edit().putInt("line-spacing",n).apply()));
        add("文本编辑器","新文件保留偏好",()->new String[]{"手动保留","自动保留","退出时询问"}[Math.max(0,Math.min(2,editor().getInt("retention",0)))],
                ()->choice("新文件保留偏好",new String[]{"手动保留","自动保留","退出时询问"},editor().getInt("retention",0),n->editor().edit().putInt("retention",n).apply()));
        add("文本编辑器","自动分页阈值",()->editor().getInt("paging-mib",2)+" MiB",()->choice("自动分页阈值",new String[]{"1 MiB","2 MiB","4 MiB","8 MiB","16 MiB"},Arrays.binarySearch(new int[]{1,2,4,8,16},editor().getInt("paging-mib",2)),n->editor().edit().putInt("paging-mib",new int[]{1,2,4,8,16}[n]).apply()));
        add("文本编辑器","自动流畅模式阈值",()->editor().getInt("smooth-threshold",200000)+" 字符",()->choice("自动流畅模式阈值",new String[]{"5 万字符","10 万字符","20 万字符","50 万字符","100 万字符"},Arrays.binarySearch(new int[]{50000,100000,200000,500000,1000000},editor().getInt("smooth-threshold",200000)),n->editor().edit().putInt("smooth-threshold",new int[]{50000,100000,200000,500000,1000000}[n]).apply()));
        editorToggle("检测外部修改或删除","detect-external",true);editorToggle("连续两次返回退出编辑器","double-back",false);
        add("文本编辑器","管理语法高亮文件",()->"查看、导入或删除自定义 .mtsx 语法",()->SyntaxFiles.choose(this,s->{},()->{},()->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),10)));
        add("文本编辑器","保留的文件",()->"恢复草稿并切换文件",()->EditorDocuments.show(this,()->{}));
        add("终端","字体大小",()->getSharedPreferences("terminal-appearance",0).getInt("textSize",13)+" sp",()->number("终端字体大小",10,30,getSharedPreferences("terminal-appearance",0).getInt("textSize",13),n->getSharedPreferences("terminal-appearance",0).edit().putInt("textSize",n).apply()));
        toggle("终端","运行时保持屏幕亮起","只在终端页面保持屏幕亮起","terminal-awake",false);
        add("备份","书签与设置备份",()->"导入、导出或备份到公共数据目录",()->startActivity(new Intent(this,BackupActivity.class)));
        rows.removeIf(item -> editorOnly != item.group.equals("文本编辑器"));
    }
    private void editorToggle(String title,String key,boolean fallback) { rows.add(new Row("文本编辑器",title,()->"","editor:"+key,fallback,null)); }
    private void render() {
        renderAt(scroll.getScrollY(), 0);
    }
    private void renderAt(int y, int direction) {
        content.removeCallbacks(renderSearch);
        int generation=++renderGeneration;
        content.animate().cancel();content.setAlpha(1f);content.setTranslationX(0f);
        content.removeAllViews();refreshers.clear();
        toolbarTitle.setText(isSearching()?(editorOnly?"搜索编辑器设置":"搜索设置"):editorOnly?"文本编辑器设置":selectedGroup.isEmpty()?"设置":category(selectedGroup).title);
        if (!isSearching() && selectedGroup.isEmpty()) renderHome();
        else renderSettings();
        scroll.post(()->{if(generation!=renderGeneration)return;scroll.scrollTo(0,y);if(direction!=0)SettingsDesign.enter(content,direction);});
    }
    private void renderHome() {
        LinearLayout appearance=SettingsDesign.card(this);
        appearance.addView(categoryRow(CATEGORIES[0]));
        LinearLayout themes=Ui.row(this);themes.setGravity(Gravity.TOP);
        themes.setPadding(Ui.dp(this,12),0,Ui.dp(this,12),Ui.dp(this,14));
        String[] modes={"light","dark","system"},names={"浅色","深色","跟随系统"};
        for(int n=0;n<modes.length;n++){
            String mode=modes[n];boolean selected=Ui.themeMode(this).equals(mode);
            LinearLayout option=Ui.column(this);option.setGravity(Gravity.CENTER);
            option.setPadding(Ui.dp(this,7),Ui.dp(this,10),Ui.dp(this,7),Ui.dp(this,10));
            android.graphics.drawable.GradientDrawable background=Ui.background(selected?SettingsDesign.tint():SettingsDesign.page(),Ui.dp(this,13));
            if(selected)background.setStroke(Ui.dp(this,1),SettingsDesign.blend(SettingsDesign.card(),Ui.ACCENT,.45f));
            option.setBackground(background);option.setForeground(SettingsDesign.ripple(this,Color.TRANSPARENT,13));
            option.addView(themePreview(mode),new LinearLayout.LayoutParams(Ui.dp(this,52),Ui.dp(this,32)));
            TextView label=Ui.text(this,names[n],12,selected?Ui.ACCENT:Ui.MUTED);label.setGravity(Gravity.CENTER);
            label.setPadding(0,Ui.dp(this,8),0,0);if(selected)label.setTypeface(null,android.graphics.Typeface.BOLD);option.addView(label);
            option.setMinimumHeight(Ui.dp(this,80));option.setFocusable(true);option.setSelected(selected);
            option.setContentDescription("主题："+names[n]);
            if(android.os.Build.VERSION.SDK_INT>=30)option.setStateDescription(selected?"已选中":"未选中");
            option.setOnClickListener(v->{if(!Ui.themeMode(this).equals(mode)){getSharedPreferences("appearance",0).edit().putString("mode",mode).apply();applyAppearance();}});
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.leftMargin=Ui.dp(this,3);p.rightMargin=Ui.dp(this,3);themes.addView(option,p);
        }
        appearance.addView(themes);
        if(Ui.material!=ThemeMaterial.CLASSIC){
            SettingsDesign.separator(appearance,16);LinearLayout style=Ui.row(this);style.setPadding(Ui.dp(this,18),0,Ui.dp(this,16),0);
            style.setMinimumHeight(Ui.dp(this,48));style.addView(Ui.text(this,"主题外观",14,Ui.TEXT),new LinearLayout.LayoutParams(0,-2,1));
            TextView value=Ui.text(this,Ui.material.title,14,Ui.ACCENT);style.addView(value);style.addView(SettingsDesign.chevron(this),new LinearLayout.LayoutParams(Ui.dp(this,22),Ui.dp(this,19)));
            style.setBackground(SettingsDesign.ripple(this,Color.TRANSPARENT,0));style.setContentDescription("选择主题外观，"+Ui.material.title);style.setFocusable(true);style.setOnClickListener(v->Ui.chooseTheme(this));appearance.addView(style);
        }
        content.addView(appearance);
        content.addView(SettingsDesign.label(this,"管理与浏览"));
        categoryCard(1,4);
        content.addView(SettingsDesign.label(this,"工具与数据"));
        categoryCard(4,CATEGORIES.length);
    }
    private View themePreview(String mode) {
        LinearLayout sample=Ui.row(this);sample.setClipToOutline(true);sample.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        sample.setBackground(Ui.background(Ui.material.tones(mode.equals("dark")).line(),Ui.dp(this,6)));
        for(int i=0;i<2;i++){
            boolean dark=mode.equals("dark")||(mode.equals("system")&&i==1);
            ThemeMaterial.Tones tones=Ui.material.tones(dark);
            LinearLayout pane=Ui.column(this);pane.setPadding(Ui.dp(this,4),Ui.dp(this,5),Ui.dp(this,4),Ui.dp(this,3));pane.setBackgroundColor(Ui.material==ThemeMaterial.CLASSIC?(dark?0xff232b2b:Color.WHITE):tones.surface());
            for(int j=0;j<3;j++){View line=new View(this);line.setBackgroundColor(j==0?Ui.material.accent(dark,Ui.accentIndex):Ui.material==ThemeMaterial.CLASSIC?(dark?0xff52615e:0xffd6e0dc):tones.line());LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(j==1?Ui.dp(this,10):-1,Ui.dp(this,3));p.bottomMargin=Ui.dp(this,4);pane.addView(line,p);}
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,1);p.setMargins(Ui.dp(this,1),Ui.dp(this,1),Ui.dp(this,1),Ui.dp(this,1));sample.addView(pane,p);
        }return sample;
    }
    private void categoryCard(int start,int end) {
        LinearLayout card=SettingsDesign.card(this);
        for(int n=start;n<end;n++){
            if(CATEGORIES[n].key.equals("文本编辑器"))continue;
            if(card.getChildCount()>0)SettingsDesign.separator(card,72);card.addView(categoryRow(CATEGORIES[n]));
        }
        content.addView(card);
    }
    private View categoryRow(Category category) {
        LinearLayout row=Ui.row(this);row.setMinimumHeight(Ui.dp(this,82));row.setPadding(Ui.dp(this,16),Ui.dp(this,14),Ui.dp(this,14),Ui.dp(this,14));
        row.addView(SettingsDesign.icon(this,category.icon),new LinearLayout.LayoutParams(Ui.dp(this,42),Ui.dp(this,42)));
        LinearLayout labels=Ui.column(this);labels.setPadding(Ui.dp(this,14),0,Ui.dp(this,10),0);
        TextView title=Ui.text(this,category.title,16,Ui.TEXT);title.setTypeface(null,android.graphics.Typeface.BOLD);labels.addView(title);
        TextView detail=Ui.text(this,category.detail,12,Ui.MUTED);detail.setPadding(0,Ui.dp(this,5),0,0);detail.setLineSpacing(Ui.dp(this,2),1f);labels.addView(detail);
        row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));row.addView(SettingsDesign.chevron(this),new LinearLayout.LayoutParams(Ui.dp(this,18),Ui.dp(this,24)));
        row.setBackground(SettingsDesign.ripple(this,Color.TRANSPARENT,0));row.setContentDescription("设置分类："+category.title);row.setFocusable(true);row.setOnClickListener(v->openCategory(category));
        return row;
    }
    private void renderSettings() {
        String filter=query.trim().toLowerCase(Locale.ROOT);
        if(isSearching()&&filter.isEmpty()){
            emptyState(editorOnly?"搜索编辑器设置":"搜索所有分类",editorOnly?"输入关键字，例如字号、Tab 宽度或语法":"输入关键字，例如主题、启动路径或文件菜单");return;
        }
        if(!isSearching()){
            Category category=category(selectedGroup);
            LinearLayout intro=Ui.row(this);intro.setPadding(Ui.dp(this,4),Ui.dp(this,12),Ui.dp(this,4),Ui.dp(this,18));
            intro.addView(SettingsDesign.icon(this,category.icon),new LinearLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,48)));
            TextView detail=Ui.text(this,category.detail,14,Ui.MUTED);detail.setPadding(Ui.dp(this,14),0,0,0);intro.addView(detail,new LinearLayout.LayoutParams(0,-2,1));content.addView(intro);
        }
        Map<String,List<Row>> sections=new LinkedHashMap<>();int count=0;
        for(Row item:rows){
            String summary=Objects.toString(item.summary.get(),"");
            if(isSearching()){
                Category category=category(item.group);
                String keywords=item.title.equals("主题材质")?" 经典 潮汐渐变 雾面玻璃 毛玻璃 暖纸 浅色 深色 跟随系统":
                        item.title.equals("文件图标样式")?" 文件夹 目录图标 线框 轮廓 色块 双色 跟随主题 原版":"";
                if(!(item.title+" "+summary+" "+item.group+" "+category.title+keywords).toLowerCase(Locale.ROOT).contains(filter))continue;
            }else if(!selectedGroup.equals(item.group))continue;
            String section=isSearching()?category(item.group).title:sectionFor(item);
            sections.computeIfAbsent(section,key->new ArrayList<>()).add(item);count++;
        }
        if(count==0){emptyState("没有匹配的设置","换一个关键字，或缩短搜索内容");return;}
        if(isSearching())content.addView(SettingsDesign.label(this,"找到 "+count+" 项设置"));
        for(var section:sections.entrySet()){
            content.addView(SettingsDesign.label(this,section.getKey()));LinearLayout card=SettingsDesign.card(this);
            for(Row item:section.getValue()){if(card.getChildCount()>0)SettingsDesign.separator(card,16);card.addView(settingRow(item));}
            content.addView(card);
        }
    }
    private String sectionFor(Row item) {
        return switch(item.group){
            case "启动" -> item.title.contains("路径")||item.title.contains("首页")?"启动目录":"访问权限";
            case "外观" -> item.title.startsWith("主题")||item.title.equals("文件图标样式")?"主题配色":item.title.equals("界面动画")?"交互反馈":"文件列表";
            case "文件管理" -> Set.of("公共数据目录","保留文件时间").contains(item.title)?"存储与保存":"浏览与打开";
            case "书签与底部工具栏" -> item.title.contains("上滑")?"底部手势":"书签排列";
            case "文本编辑器" -> editorSection(item);
            case "终端" -> "终端偏好";
            default -> "备份与恢复";
        };
    }
    private String editorSection(Row item) {
        if(Set.of("编辑时生成备份文件","新文件保留偏好","自动分页阈值","自动流畅模式阈值","检测外部修改或删除","连续两次返回退出编辑器","保留的文件").contains(item.title))return "文件与性能";
        if(Set.of("语法高亮","管理语法高亮文件","编辑器菜单排序","快捷符号栏","编辑快捷符号栏").contains(item.title))return "语法与工具";
        if(Set.of("Tab 宽度","自动缩进","使用制表符缩进","括号自动补全","启用放大镜","双指缩放","回车应用补全","补全窗口最大高度").contains(item.title))return "输入与补全";
        return "显示与排版";
    }
    private View settingRow(Row item) {
        String summary=Objects.toString(item.summary.get(),"");
        LinearLayout row=Ui.row(this);row.setPadding(Ui.dp(this,16),Ui.dp(this,15),Ui.dp(this,16),Ui.dp(this,15));row.setMinimumHeight(Ui.dp(this,62));row.setBackground(SettingsDesign.ripple(this,Color.TRANSPARENT,0));
        LinearLayout labels=Ui.column(this);labels.setPadding(0,0,Ui.dp(this,14),0);TextView title=Ui.text(this,item.title,15,Ui.TEXT);labels.addView(title);
        TextView detail=Ui.text(this,summary,12,Ui.MUTED);detail.setPadding(0,Ui.dp(this,5),0,0);detail.setLineSpacing(Ui.dp(this,2),1f);detail.setVisibility(summary.isEmpty()?View.GONE:View.VISIBLE);labels.addView(detail);row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        refreshers.add(()->{String updated=Objects.toString(item.summary.get(),"");detail.setText(updated);detail.setVisibility(updated.isEmpty()?View.GONE:View.VISIBLE);row.setContentDescription(item.title+(updated.isEmpty()?"":"，"+updated));});
        if(item.key!=null){
            boolean isEditor=item.key.startsWith("editor:");SharedPreferences values=isEditor?editor():prefs();String key=isEditor?item.key.substring(7):item.key;
            Switch toggle=new Switch(this);toggle.setChecked(values.getBoolean(key,item.fallback));toggle.setClickable(false);toggle.setFocusable(false);toggle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            int[][] states={new int[]{android.R.attr.state_checked},new int[]{}};
            toggle.setThumbTintList(new android.content.res.ColorStateList(states,new int[]{Ui.ACCENT,Ui.material==ThemeMaterial.CLASSIC?(Ui.dark?0xffc5d0cc:Color.WHITE):Ui.dark?Ui.MUTED:Ui.SURFACE}));
            toggle.setTrackTintList(new android.content.res.ColorStateList(states,new int[]{SettingsDesign.blend(SettingsDesign.card(),Ui.ACCENT,.36f),Ui.material==ThemeMaterial.CLASSIC?(Ui.dark?0xff52615d:0xffcbd6d2):Ui.LINE}));
            row.addView(toggle);
            Runnable refresh=()->{toggle.setChecked(values.getBoolean(key,item.fallback));if(android.os.Build.VERSION.SDK_INT>=30)row.setStateDescription(toggle.isChecked()?"开启":"关闭");};refreshers.add(refresh);
            row.setOnClickListener(v->{boolean enabled=!values.getBoolean(key,item.fallback);values.edit().putBoolean(key,enabled).apply();if(key.equals("root"))RootAccess.setAutomatic(enabled);refresh.run();Ui.configure(this);if(!Ui.motionEnabled()){content.animate().cancel();content.setAlpha(1f);content.setTranslationX(0f);}});refresh.run();
            row.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName(Switch.class.getName());info.setCheckable(true);info.setChecked(toggle.isChecked());}});
        }else{
            row.addView(SettingsDesign.chevron(this),new LinearLayout.LayoutParams(Ui.dp(this,18),Ui.dp(this,24)));
            row.setOnClickListener(v->{hideKeyboard();item.action.run();});
        }
        row.setFocusable(true);row.setContentDescription(item.title+(summary.isEmpty()?"":"，"+summary));return row;
    }
    private void emptyState(String title,String detail) {
        LinearLayout empty=SettingsDesign.card(this);empty.setPadding(Ui.dp(this,24),Ui.dp(this,32),Ui.dp(this,24),Ui.dp(this,32));empty.setGravity(Gravity.CENTER);
        empty.addView(SettingsDesign.icon(this,R.drawable.ic_search),new LinearLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,48)));
        TextView label=Ui.text(this,title,17,Ui.TEXT);label.setTypeface(null,android.graphics.Typeface.BOLD);label.setGravity(Gravity.CENTER);label.setPadding(0,Ui.dp(this,18),0,Ui.dp(this,8));empty.addView(label);
        TextView description=Ui.text(this,detail,13,Ui.MUTED);description.setGravity(Gravity.CENTER);description.setLineSpacing(Ui.dp(this,3),1f);empty.addView(description);content.addView(empty);
    }
    private void styleChrome() {
        SettingsDesign.chrome(this,body,toolbar);
        search.setBackground(Ui.material==ThemeMaterial.CLASSIC?Ui.background(SettingsDesign.card(),Ui.dp(this,14)):Ui.material.card(this,SettingsDesign.card(),SettingsDesign.radius(14)));search.setTextColor(Ui.TEXT);search.setHintTextColor(Ui.MUTED);
    }
    private void choice(String title,String[] values,int selected,java.util.function.IntConsumer action) { hideKeyboard(); MaterialDialogs.builder(this).setTitle(title).setSingleChoiceItems(values,selected,(d,n)->{action.accept(n);d.dismiss();render();}).setNegativeButton("取消",null).show(); }
    private void hideKeyboard() { View focus=getCurrentFocus();if(focus!=null){((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(focus.getWindowToken(),0);focus.clearFocus();} }
    private void number(String title,int min,int max,int value,java.util.function.IntConsumer action) {
        AlertDialog.Builder dialog=MaterialDialogs.builder(this);
        android.content.Context c=SettingsDesign.themed()?dialog.getContext():this;
        LinearLayout body=SettingsDesign.themed()?SettingsDesign.form(c):Ui.column(c);
        TextView label=Ui.text(c,Integer.toString(value),18,SettingsDesign.themed()?Ui.ACCENT:Ui.TEXT);
        if(SettingsDesign.themed()){label.setGravity(Gravity.CENTER);label.setTypeface(null,android.graphics.Typeface.BOLD);}
        body.addView(label);SeekBar bar=SettingsDesign.slider(c,title);bar.setMax(max-min);bar.setProgress(value-min);body.addView(bar);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean u){label.setText(Integer.toString(min+p));}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});
        dialog.setTitle(title).setView(body).setNegativeButton("取消",null).setPositiveButton("保存",(d,n)->{action.accept(min+bar.getProgress());render();}).show();
    }
    private void dateFormat(){EditText input=Ui.input(this,"yyyy-MM-dd HH:mm:ss");input.setText(prefs().getString("date-format","yyyy-MM-dd HH:mm:ss"));AlertDialog dialog=MaterialDialogs.builder(this).setTitle("日期时间格式").setView(input).setNegativeButton("取消",null).setPositiveButton("保存",null).create();dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{String value=input.getText().toString().trim();try{if(value.isEmpty()||value.length()>80)throw new IllegalArgumentException();new SimpleDateFormat(value,Locale.CHINA).format(new Date());prefs().edit().putString("date-format",value).apply();dialog.dismiss();render();}catch(IllegalArgumentException e){input.setError("日期格式无效，例如 yyyy-MM-dd HH:mm:ss");}}));dialog.show();}
    private void refreshValues(){for(Runnable update:refreshers)update.run();}
    private void authorizeDirectory(){
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                |Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try{startActivityForResult(intent,REQUEST_DIRECTORY);}catch(ActivityNotFoundException unavailable){Tasks.showError(this,new java.io.IOException("系统目录选择器不可用",unavailable));}
    }
    @Override protected void onResume(){super.onResume();if(content!=null)refreshValues();}
    @Override protected void onPaletteChanged(){if(content!=null){styleChrome();render();}}
    @Override protected void onDestroy(){renderGeneration++;if(content!=null){content.removeCallbacks(renderSearch);content.animate().cancel();}super.onDestroy();}
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused&&content!=null)refreshValues();}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK||data==null||data.getData()==null)return;
        if(request==10&&editorOnly)Tasks.run(this,()->new FileStore(this).document(data.getData()),node->SyntaxFiles.install(this,node));
        else if(request==REQUEST_DIRECTORY&&!editorOnly){
            android.net.Uri uri=data.getData();int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            Tasks.run(this,()->{
                if((flags&Intent.FLAG_GRANT_READ_URI_PERMISSION)==0)throw new java.io.IOException("系统未授予此目录的读取权限");
                int grant=Intent.FLAG_GRANT_READ_URI_PERMISSION;
                if((flags&Intent.FLAG_GRANT_WRITE_URI_PERMISSION)!=0)grant|=Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                try{getContentResolver().takePersistableUriPermission(uri,grant);}
                catch(SecurityException denied){throw new java.io.IOException("无法保留此目录的授权，请重新选择可授权的目录",denied);}
                android.net.Uri document=android.provider.DocumentsContract.buildDocumentUriUsingTree(uri,android.provider.DocumentsContract.getTreeDocumentId(uri));
                FileStore.Node node=new FileStore(this).document(document);
                if(!node.directory)throw new java.io.IOException("授权位置不是目录");
                new DirectoryPlaces(getApplicationContext()).bookmark(DirectoryPlaces.Entry.from(node));
                return node;
            },node->Tasks.toast(this,"已授权并加入书签："+node.name));
        }
    }
}

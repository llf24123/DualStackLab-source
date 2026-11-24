package org.example.filemanager;

import android.content.Context;
import android.graphics.Color;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import com.florianingerl.util.regex.Pattern;

/** Parser for the documented declarative MTSX rules. Unsupported constructs fail at import. */
final class MtsxSyntax {
    static final int LIMIT=256*1024;
    record Regex(String text){}
    record Include(String name){}
    record Join(Object a,Object b){}
    record Definition(String name,Object value){}
    record Inherit(String name,String parent){}
    record ColorToken(String value){}
    record Flags(String value){}
    record Builtin(String name){}
    record Variable(String name){}
    record Export(int group,String name){}
    record CaptureValues(List<Object> values){}
    private record Captures(Map<Integer,Integer> styles,Map<Integer,List<Rule>> children){}
    record Style(int id,int light,int dark,boolean bold,boolean italic,int lightBackground,int darkBackground,boolean underline,boolean strike){}
    static final class Rule {
        Pattern match,end; int style,inner,endPriority; boolean matchEndFirst,allGroups,endAllGroups; Map<Integer,Integer> groups=Map.of(),endGroups=Map.of(); List<Rule> children=List.of();
        Object endExpression;Map<String,Integer> exports=Map.of();
        String group;Map<Integer,List<Rule>> captureChildren=Map.of(),endCaptureChildren=Map.of();
        Rule origin;
    }
    final String name;final List<String> extensions;final List<Rule> rules;final Map<String,Style> styles=new LinkedHashMap<>();
    final List<String> brackets;final String commentStart,commentEnd;
    private final Map<String,Object> defines=new LinkedHashMap<>();private final boolean ignoreCase;private int ruleCount,compileDepth;
    private MtsxSyntax(Map<String,Object> root)throws IOException{
        requireKeys(root,Set.of("name","hide","ignoreCase","styles","colors","comment","bracketPairs","contains","defines"));
        List<Object> names=array(root.get("name"));if(names.isEmpty()||!(names.get(0)instanceof String s)||s.isBlank())throw new IOException("语法缺少名称");name=s;
        extensions=new ArrayList<>();for(int i=1;i<names.size();i++){String ext=string(names.get(i));if(!ext.startsWith(".")||ext.contains("/")||ext.length()>50)throw new IOException("语法后缀无效");extensions.add(ext.toLowerCase(Locale.ROOT));}
        ignoreCase=Boolean.TRUE.equals(root.get("ignoreCase"));
        builtinStyles();parseStyles(array(root.getOrDefault("styles",root.get("colors"))));
        for(Object value:array(root.get("defines"))){if(!(value instanceof Definition d)||defines.put(d.name,d.value)!=null)throw new IOException("defines 需使用唯一名称");}
        Map<String,Object> comment=root.get("comment")==null?Map.of():object(root.get("comment"));requireKeys(comment,Set.of("startsWith","endsWith","insertSpace"));
        commentStart=comment.containsKey("startsWith")?string(comment.get("startsWith")):null;commentEnd=comment.containsKey("endsWith")?string(comment.get("endsWith")):null;
        brackets=new ArrayList<>();for(Object pair:array(root.get("bracketPairs"))){String value=string(pair);if(value.length()!=2)throw new IOException("括号对必须为两个字符");brackets.add(value);}
        List<Rule> all=new ArrayList<>(compile(array(root.get("contains")),new HashSet<>()));
        if(commentStart!=null&&!commentStart.isEmpty()){Rule rule=new Rule();rule.style=style("comment");rule.inner=rule.style;
            rule.match=pattern(Pattern.quote(commentStart)+(commentEnd==null?".*":""));if(commentEnd!=null)rule.end=pattern(Pattern.quote(commentEnd));all.add(0,rule);}
        rules=List.copyOf(all);
    }
    static MtsxSyntax parse(String source)throws IOException{
        if(source.length()>LIMIT)throw new IOException("语法文件超过 256 KiB");Parser parser=new Parser(source);Object root=parser.value();if(parser.has())throw parser.error("文件结尾有多余内容");return new MtsxSyntax(object(root));
    }
    private void builtinStyles(){
        add("normal",EditorColorScheme.TEXT_NORMAL,0xff202020,0xffe8e8e8);add("comment",EditorColorScheme.COMMENT,0xff71816c,0xff92a088);
        add("keyword",EditorColorScheme.KEYWORD,0xff9b2585,0xffd396d4);add("string",EditorColorScheme.ATTRIBUTE_VALUE,0xff26753b,0xffa4ce91);
        add("number",EditorColorScheme.LITERAL,0xff255fac,0xff85bbeb);add("type",EditorColorScheme.HTML_TAG,0xff247c8a,0xff8fd6df);
        add("variable",EditorColorScheme.IDENTIFIER_VAR,0xff346487,0xffa3ccec);add("function",EditorColorScheme.FUNCTION_NAME,0xff805f1d,0xffdcc48b);
        add("operator",EditorColorScheme.OPERATOR,0xff505050,0xffc5c5c5);add("annotation",EditorColorScheme.ANNOTATION,0xff8d5e2e,0xffdfb788);
        styles.put("constant",styles.get("number"));styles.put("tag",styles.get("type"));styles.put("attr",styles.get("variable"));styles.put("symbol",styles.get("operator"));
        styles.put("default",styles.get("normal"));styles.put("meta",styles.get("annotation"));
        add("error",480,0xffc62828,0xffff8a80);add("strEscape",481,0xff9b2585,0xffd396d4);
        styles.put("keyword2",styles.get("keyword"));styles.put("label",styles.get("function"));styles.put("propKey",styles.get("variable"));styles.put("propVal",styles.get("string"));styles.put("tagName",styles.get("type"));styles.put("attrName",styles.get("variable"));styles.put("namespace",styles.get("type"));
    }
    private void add(String name,int id,int light,int dark){styles.put(name,new Style(id,light,dark,false,false,0,0,false,false));}
    private void parseStyles(List<Object> values)throws IOException{
        for(int i=0;i<values.size();){Object key=values.get(i++);String name;Style parent=styles.get("normal");
            if(key instanceof Inherit inherit){name=inherit.name;parent=styles.get(inherit.parent);if(parent==null)throw new IOException("未知继承风格："+inherit.parent);}else name=string(key);
            int light=parent.light,dark=parent.dark,lightBackground=parent.lightBackground,darkBackground=parent.darkBackground;boolean bold=parent.bold,italic=parent.italic,underline=parent.underline,strike=parent.strike;int colors=0;
            while(i<values.size()&&(values.get(i)instanceof ColorToken||values.get(i)instanceof Flags)){
                Object v=values.get(i++);if(v instanceof ColorToken color){if(++colors>2)throw new IOException("每个风格只接受日间、夜间两组颜色");try{int split=color.value.indexOf('#',1);int c=Color.parseColor(split<0?color.value:color.value.substring(0,split));int bg=split<0?0:Color.parseColor(color.value.substring(split));if(colors==1){light=dark=c;lightBackground=darkBackground=bg;}else{dark=c;darkBackground=bg;}}catch(IllegalArgumentException e){throw new IOException("颜色格式无效："+color.value);}}
                else{String flags=((Flags)v).value;if(!flags.matches("[BIUS_]*"))throw new IOException("文字风格标记无效："+flags);bold=flags.contains("B");italic=flags.contains("I");underline=flags.contains("U");strike=flags.contains("S");}
            }styles.put(name,new Style(512+styles.size(),light,dark,bold,italic,lightBackground,darkBackground,underline,strike));
        }
    }
    private List<Rule> compile(List<Object> values,Set<String> stack)throws IOException{
        return compile(values,stack,style("normal"));
    }
    private List<Rule> compile(List<Object> values,Set<String> stack,int inherited)throws IOException{
        if(++compileDepth>32){compileDepth--;throw new IOException("匹配器嵌套超过 32 层");}
        try{return compileRules(values,stack,inherited);}finally{compileDepth--;}
    }
    private List<Rule> compileRules(List<Object> values,Set<String> stack,int inherited)throws IOException{
        List<Rule> out=new ArrayList<>();for(Object value:values){if(value instanceof Builtin builtin){out.addAll(compile(builtin(builtin.name),stack,inherited));continue;}Map<String,Object> map=object(value);requireKeys(map,Set.of("builtin","match","start","end","style","color","childrenStyle","contains","include","number","iSuffixes","fSuffixes","group","matchEndFirst","endPriority","mustMatchEnd","recordAllGroups"));
            if(map.get("builtin") instanceof Builtin builtin){List<Rule> built=compile(builtin(builtin.name),stack,inherited);if(map.containsKey("style"))for(Rule rule:built){rule.style=rule.inner=style(map.get("style"));rule.groups=Map.of(0,rule.style);}out.addAll(built);continue;}
            if(map.containsKey("include")){String name=string(map.get("include"));Object target=defines.get(name);if(target==null||!stack.add(name))throw new IOException("未知或递归 include："+name);out.addAll(compile(target instanceof List<?>?array(target):List.of(target),stack,inherited));stack.remove(name);continue;}
            if(map.containsKey("group")){
                requireKeys(map,Set.of("group","style","contains"));
                for(String key:map.keySet())if(key.matches("\\d+"))throw new IOException("group 不支持捕获组属性："+key);
                String kind=string(map.get("group"));if(!Set.of("first","select","link","linkAll").contains(kind))throw new IOException("未知 group 类型："+kind);
                if(++ruleCount>256)throw new IOException("匹配器超过 256 项");
                Rule group=new Rule();group.group=kind.equals("first")?"select":kind;group.style=group.inner=map.containsKey("style")?style(map.get("style")):inherited;
                group.children=compile(array(map.get("contains")),stack,group.style);
                if(group.children.isEmpty())throw new IOException("group contains 不能为空");
                if(!group.group.equals("select"))for(Rule child:group.children)if(!singleMatch(child))throw new IOException("link/linkAll 暂不支持 start-end 子匹配器；请使用 match 或 select 组合");
                out.add(group);continue;
            }
            if(++ruleCount>256)throw new IOException("匹配器超过 256 项");Rule r=new Rule();r.style=map.containsKey("style")?style(map.get("style")):map.containsKey("color")?style(map.get("color")):map.containsKey("number")?style("number"):inherited;r.inner=map.containsKey("childrenStyle")?style(map.get("childrenStyle")):r.style;r.allGroups=Boolean.TRUE.equals(map.get("recordAllGroups"));
            if(map.containsKey("start")){
                Map<String,Object> start=object(map.get("start")),end=object(map.get("end"));requireKeys(start,Set.of("match","recordAllGroups","export"));requireKeys(end,Set.of("match","recordAllGroups"));
                r.match=pattern(expression(start.get("match"),new HashSet<>()));
                Map<String,Integer> exports=new LinkedHashMap<>();Set<Integer> exportedGroups=new HashSet<>();
                if(start.containsKey("export"))for(Object item:array(start.get("export"))){if(!(item instanceof Export export)||export.group<0||export.group>r.match.matcher("").groupCount()||export.name.isBlank()||exports.put(export.name,export.group)!=null||!exportedGroups.add(export.group))throw new IOException("export 捕获组或变量名无效");}
                if(exports.isEmpty())r.end=pattern(expression(end.get("match"),new HashSet<>()));
                else{r.exports=Map.copyOf(exports);r.endExpression=end.get("match");Map<String,String> dummy=new HashMap<>();exports.keySet().forEach(k->dummy.put(k,"x"));Set<String> used=new HashSet<>();StringBuilder template=new StringBuilder();dynamicExpression(r.endExpression,dummy,used,template);if(!used.equals(exports.keySet()))throw new IOException("每个 export 变量都必须在对应终点使用");r.end=pattern(template.toString());}
                Captures startCaptures=captures(start,r.style,stack),endCaptures=captures(end,r.style,stack);
                r.groups=startCaptures.styles;r.captureChildren=startCaptures.children;r.endGroups=endCaptures.styles;r.endCaptureChildren=endCaptures.children;
                r.allGroups=Boolean.TRUE.equals(start.get("recordAllGroups"));r.endAllGroups=Boolean.TRUE.equals(end.get("recordAllGroups"));r.matchEndFirst=Boolean.TRUE.equals(map.get("matchEndFirst"));
                if(Boolean.TRUE.equals(map.get("mustMatchEnd")))throw new IOException("暂不支持跨行预判 mustMatchEnd");
                r.children=compile(array(map.get("contains")),stack,r.inner);
                try{r.endPriority=Integer.parseInt(Objects.toString(map.getOrDefault("endPriority","0")));if(r.endPriority<0||r.endPriority>r.children.size())throw new NumberFormatException();}catch(NumberFormatException e){throw new IOException("endPriority 超出子匹配器范围");}
                if(Boolean.TRUE.equals(map.get("matchEndFirst")))r.endPriority=0;
                for(int group:r.endGroups.keySet())if(group>r.end.matcher("").groupCount())throw new IOException("终点捕获组超出范围："+group);
                for(int group:r.endCaptureChildren.keySet())if(group>r.end.matcher("").groupCount())throw new IOException("终点捕获组超出范围："+group);
            }else if(map.containsKey("number")){
                String flags=string(map.get("number"));if(!flags.matches("[0-9|FE_P']+"))throw new IOException("number 标记无效");
                List<String> numbers=new ArrayList<>();String digits="[0-9"+(flags.contains("_")?"_":"")+(flags.contains("'")?"'":"")+"]";
                if(flags.contains("16"))numbers.add("0[xX][0-9a-fA-F_]+(?:\\.[0-9a-fA-F_]+)?(?:[pP][+-]?\\d+)?");
                if(flags.contains("2"))numbers.add("0[bB][01_]+");if(flags.contains("8"))numbers.add("0[oO]?[0-7_]+");
                if(flags.contains("10")||flags.contains("F"))numbers.add(digits+"+(?:\\."+digits+"+)?(?:[eE][+-]?"+digits+"+)?");
                if(numbers.isEmpty())throw new IOException("number 未指定数字类型");String suffix=Objects.toString(map.get("iSuffixes"),"")+Objects.toString(map.get("fSuffixes"),"");r.match=pattern("\\b(?:"+String.join("|",numbers)+")"+(suffix.isEmpty()?"":"["+Pattern.quote(suffix)+"]*")+"\\b");
                r.groups=Map.of(0,r.style);
            }else{r.match=pattern(expression(map.get("match"),new HashSet<>()));Captures captures=captures(map,r.style,stack);r.groups=captures.styles;r.captureChildren=captures.children;}
            for(int group:r.groups.keySet())if(group>r.match.matcher("").groupCount())throw new IOException("捕获组超出范围："+group);
            for(int group:r.captureChildren.keySet())if(group>r.match.matcher("").groupCount())throw new IOException("捕获组超出范围："+group);
            out.add(r);
        }return List.copyOf(out);
    }
    private static boolean singleMatch(Rule rule){if(rule.end!=null)return false;for(Rule child:rule.children)if(!singleMatch(child))return false;return true;}
    private Captures captures(Map<String,Object> map,int fallback,Set<String> stack)throws IOException{
        Map<Integer,Integer> groups=new TreeMap<>();groups.put(0,fallback);Map<Integer,List<Rule>> children=new TreeMap<>();
        Map<Integer,List<Object>> values=new TreeMap<>();
        for(var entry:map.entrySet())if(entry.getKey().matches("\\d+")){
            int index;try{index=Integer.parseInt(entry.getKey());}catch(NumberFormatException e){throw new IOException("捕获组编号无效："+entry.getKey());}
            if(values.containsKey(index))throw new IOException("捕获组编号重复："+entry.getKey());
            List<Object> items=entry.getValue()instanceof CaptureValues repeated?repeated.values:List.of(entry.getValue());values.put(index,items);
            boolean styled=false;for(Object value:items)if(value instanceof String){if(styled)throw new IOException("捕获组风格重复："+index);groups.put(index,style(value));styled=true;}
        }
        for(var entry:values.entrySet()){
            List<Object> rules=new ArrayList<>();for(Object value:entry.getValue())if(!(value instanceof String)){
                if(!(value instanceof Map<?,?>)&&!(value instanceof Builtin))throw new IOException("捕获组只接受风格名或子匹配器");rules.add(value);
            }
            if(!rules.isEmpty())children.put(entry.getKey(),compile(rules,stack,groups.getOrDefault(entry.getKey(),groups.get(0))));
        }
        return new Captures(Collections.unmodifiableMap(groups),Collections.unmodifiableMap(children));
    }
    private int style(Object value)throws IOException{Style found=styles.get(string(value));if(found==null)throw new IOException("未知风格："+value);return found.id;}
    private Pattern pattern(String expression)throws IOException{
        if(expression.length()>4096)throw new IOException("正则表达式超过 4096 字符");try{return Pattern.compile(expression,Pattern.MULTILINE|(ignoreCase?Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE:0));}catch(IllegalArgumentException e){throw new IOException("语法正则表达式无效："+e.getMessage());}
    }
    private List<Object> builtin(String name)throws IOException {
        if(name.endsWith("_NUMBER")||name.equals("PROGRAM_NUMBER2")){
            if(!Set.of("NORMAL_NUMBER","PROGRAM_NUMBER","PROGRAM_NUMBER2","JAVA_NUMBER","C_NUMBER").contains(name))throw new IOException("未知内置匹配器："+name);
            String pattern=name.equals("NORMAL_NUMBER")?"[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?":"\\b(?:0[xX][0-9a-fA-F]+(?:\\.[0-9a-fA-F]*)?(?:[pP][+-]?\\d+)?|"+(name.equals("PROGRAM_NUMBER2")?"":"0[bB][01_]+|")+"\\d[\\d_]*(?:\\.[\\d_]*)?(?:[eE][+-]?[\\d_]+)?)[uUlLfFdD]*\\b";
            return List.of(Map.of("match",new Regex(pattern),"style","number"));
        }
        if(name.equals("ESCAPED_CHAR")||name.equals("JAVA_ESCAPED_CHAR")||name.equals("C_ESCAPED_CHAR"))return List.of(Map.of("match",new Regex("\\\\(?:u[0-9a-fA-F]{4}|x[0-9a-fA-F]{2}|[0-7]{1,3}|[^\\r\\n])"),"style","strEscape"));
        String kind=name.replaceFirst("^(JAVA_|C_)","");
        if(!Set.of("SINGLE_QUOTED_STRING","DOUBLE_QUOTED_STRING","QUOTED_STRING").contains(kind))throw new IOException("未知内置匹配器："+name);
        List<Object> result=new ArrayList<>();for(String quote:kind.equals("SINGLE_QUOTED_STRING")?List.of("'"):kind.equals("DOUBLE_QUOTED_STRING")?List.of("\""):List.of("'","\""))
            result.add(Map.of("start",Map.of("match",new Regex(Pattern.quote(quote))),"end",Map.of("match",new Regex(Pattern.quote(quote)+"|$")),"style","string","contains",List.of(Map.of("match",new Regex("\\\\."),"style","strEscape"))));
        return result;
    }
    private String expression(Object value,Set<String> stack)throws IOException{
        if(value instanceof String text)return text;if(value instanceof Regex regex)return regex.text;
        if(value instanceof Join join)return expression(join.a,stack)+expression(join.b,stack);
        if(value instanceof Include include){if(!stack.add(include.name)||!defines.containsKey(include.name))throw new IOException("未知或递归正则 include："+include.name);String text=expression(defines.get(include.name),stack);stack.remove(include.name);return text;}
        throw new IOException("缺少正则表达式");
    }
    private static void dynamicExpression(Object value,Map<String,String> variables,Set<String> used,StringBuilder out)throws IOException {
        if(value instanceof Join join){dynamicExpression(join.a,variables,used,out);dynamicExpression(join.b,variables,used,out);}
        else if(value instanceof Variable variable){
            if(!variables.containsKey(variable.name))throw new IOException("终点引用了未导出的变量："+variable.name);
            boolean escaped=false,quoted=false;int brackets=0;
            for(int i=0;i<out.length();i++){char c=out.charAt(i);if(quoted){if(c=='\\'&&i+1<out.length()&&out.charAt(i+1)=='E'){quoted=false;i++;}continue;}
                if(escaped){if(c=='Q')quoted=true;escaped=false;continue;}if(c=='\\')escaped=true;else if(c=='[')brackets++;else if(c==']'&&brackets>0)brackets--;}
            if(quoted||escaped||brackets>0)throw new IOException("var 不能位于字符类、转义或引用块中");
            used.add(variable.name);out.append("(?:").append(Pattern.quote(variables.get(variable.name))).append(')');
        }else if(value instanceof Regex regex)out.append(regex.text);else if(value instanceof String text)out.append(text);else throw new IOException("动态终点仅接受正则、字符串与 var 拼接");
        if(out.length()>4096)throw new IOException("动态终点超过 4096 字符");
    }
    Rule resolve(Rule template,com.florianingerl.util.regex.Matcher start)throws IOException {
        if(template.exports.isEmpty())return template;Map<String,String> values=new HashMap<>();
        for(var entry:template.exports.entrySet()){int group=entry.getValue();if(start.start(group)<start.start()||start.end(group)>start.end())throw new IOException("导出的捕获组没有参与起点匹配");values.put(entry.getKey(),start.group(group));}
        StringBuilder expression=new StringBuilder();dynamicExpression(template.endExpression,values,new HashSet<>(),expression);
        Rule rule=new Rule();rule.origin=template;rule.match=template.match;rule.end=pattern(expression.toString());rule.style=template.style;rule.inner=template.inner;rule.endPriority=template.endPriority;
        rule.matchEndFirst=template.matchEndFirst;rule.allGroups=template.allGroups;rule.endAllGroups=template.endAllGroups;rule.groups=template.groups;rule.endGroups=template.endGroups;rule.captureChildren=template.captureChildren;rule.endCaptureChildren=template.endCaptureChildren;rule.children=template.children;return rule;
    }
    private static void requireKeys(Map<String,Object> map,Set<String> allowed)throws IOException{for(String key:map.keySet())if(!allowed.contains(key)&&!key.matches("\\d+"))throw new IOException("暂不支持 MTSX 属性："+key);}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value)throws IOException{if(!(value instanceof Map<?,?>))throw new IOException("需要 { } 属性块");return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked") private static List<Object> array(Object value)throws IOException{if(value==null)return List.of();if(!(value instanceof List<?>))throw new IOException("需要 [ ] 列表");return (List<Object>)value;}
    private static String string(Object value)throws IOException{if(!(value instanceof String text))throw new IOException("需要字符串");return text;}
    static File folder(Context context){return new File(context.getFilesDir(),"syntax");}
    static List<File> installed(Context context){File[] files=folder(context).listFiles((d,n)->n.endsWith(".mtsx"));return files==null?List.of():Arrays.asList(files);}
    static MtsxSyntax read(File file)throws IOException{if(file.length()>LIMIT)throw new IOException("语法文件过大");return parse(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));}
    static MtsxSyntax forName(Context context,String filename)throws IOException{String lower=Objects.toString(filename,"").toLowerCase(Locale.ROOT);List<File> files=new ArrayList<>(installed(context));files.sort(Comparator.comparing(File::getName));for(File file:files){try{MtsxSyntax syntax=read(file);if(syntax.extensions.stream().anyMatch(lower::endsWith))return syntax;}catch(IOException error){android.util.Log.w("MtsxSyntax","跳过损坏的语法文件："+file.getName(),error);}}return null;}
    static MtsxSyntax install(Context context,byte[] bytes)throws Exception{
        if(bytes.length>LIMIT)throw new IOException("语法文件超过 256 KiB");MtsxSyntax syntax=parse(new String(bytes,StandardCharsets.UTF_8));File folder=folder(context);if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("无法创建语法目录");
        String key=HexDocument.hex(java.security.MessageDigest.getInstance("SHA-256").digest(syntax.name.getBytes(StandardCharsets.UTF_8)));android.util.AtomicFile target=new android.util.AtomicFile(new File(folder,key+".mtsx"));FileOutputStream out=null;
        try{out=target.startWrite();out.write(bytes);target.finishWrite(out);}catch(IOException e){target.failWrite(out);throw e;}return syntax;
    }
    private static final class Parser{
        final String source;int at;Object token;int depth;Parser(String source)throws IOException{this.source=source;next();}
        boolean has(){return token!=null;}IOException error(String message){return new IOException(message+"（字符 "+at+"）");}
        boolean is(String s){return token instanceof Character c&&s.length()==1&&c==s.charAt(0);}
        void expect(String s)throws IOException{if(!is(s))throw error("需要 "+s);next();}
        void next()throws IOException{
            while(at<source.length()){
                char c=source.charAt(at);if(Character.isWhitespace(c)||c==','){at++;continue;}
                if(c=='/'&&at+1<source.length()&&source.charAt(at+1)=='/'){at+=2;while(at<source.length()&&source.charAt(at)!='\n')at++;continue;}
                if(c=='/'&&at+1<source.length()&&source.charAt(at+1)=='*'){int end=source.indexOf("*/",at+2);if(end<0)throw error("注释未结束");at=end+2;continue;}break;
            }
            if(at==source.length()){token=null;return;}char c=source.charAt(at++);
            if(c=='"'||c=='/'){
                StringBuilder out=new StringBuilder();boolean ended=false;
                while(at<source.length()){char v=source.charAt(at++);if(v==c){ended=true;break;}if(v=='\\'){
                    if(at==source.length())throw error("转义未结束");char e=source.charAt(at++);
                    if(c=='/'){if(e=='/')out.append('/');else out.append('\\').append(e);}
                    else switch(e){case 'n'->out.append('\n');case 'r'->out.append('\r');case 't'->out.append('\t');case 'b'->out.append('\b');case 'f'->out.append('\f');case '"','\\','/'->out.append(e);case 'u'->{if(at+4>source.length())throw error("Unicode 转义无效");try{out.append((char)Integer.parseInt(source.substring(at,at+4),16));}catch(NumberFormatException x){throw error("Unicode 转义无效");}at+=4;}default->throw error("未知转义 "+e);}
                }else out.append(v);}if(!ended)throw error("字符串或正则未结束");token=c=='/'?new Regex(out.toString()):out.toString();return;
            }
            if(c=='#'){int start=at,end=source.indexOf('#',at);if(end>at&&source.substring(at,end).matches("[A-Z][A-Z0-9_]+")&&!source.substring(at,end).matches("(?:[A-Fa-f0-9]{6}|[A-Fa-f0-9]{8})")){token=new Builtin(source.substring(at,end));at=end+1;return;}while(at<source.length()&&Character.digit(source.charAt(at),16)>=0)at++;if(at<source.length()&&source.charAt(at)=='#'){at++;while(at<source.length()&&Character.digit(source.charAt(at),16)>=0)at++;}token=new ColorToken("#"+source.substring(start,at));return;}
            if(c=='@'){int start=at;while(at<source.length()&&(Character.isLetter(source.charAt(at))||source.charAt(at)=='_'))at++;token=new Flags(source.substring(start,at));return;}
            if(Character.isLetterOrDigit(c)||c=='_'){int start=at-1;while(at<source.length()&&(Character.isLetterOrDigit(source.charAt(at))||source.charAt(at)=='_'))at++;String word=source.substring(start,at);token=word.equals("true")?Boolean.TRUE:word.equals("false")?Boolean.FALSE:word;return;}
            token=c;
        }
        Object value()throws IOException{
            if(++depth>48)throw error("语法嵌套过深");Object value;
            if(is("{")){next();Map<String,Object> map=new LinkedHashMap<>();while(!is("}")){String key=string(token);next();expect(":");Object item=value();if(key.equals("export")){if(!(item instanceof Export))throw error("export 需要 组号 => 变量名");@SuppressWarnings("unchecked") List<Object> exports=(List<Object>)map.computeIfAbsent(key,k->new ArrayList<>());exports.add(item);}else if(key.matches("\\d+")){Object old=map.get(key);if(old==null)map.put(key,item);else{List<Object> values=new ArrayList<>();if(old instanceof CaptureValues repeated)values.addAll(repeated.values);else values.add(old);values.add(item);map.put(key,new CaptureValues(values));}}else if(map.put(key,item)!=null)throw error("重复属性 "+key);}expect("}");value=map;}
            else if(is("[")){next();List<Object> list=new ArrayList<>();while(!is("]")){Object item=value();if(is(":")){next();item=new Definition(string(item),value());}list.add(item);}expect("]");value=list;}
            else{value=token;if(value==null||value instanceof Character)throw error("需要属性值");next();
                if(is("(")){next();if("include".equals(value)){value=new Include(string(token));next();expect(")");}
                    else if("var".equals(value)){value=new Variable(string(token));next();expect(")");}
                    else if("keywordsToRegex".equals(value)){List<String> words=new ArrayList<>();while(!is(")")){for(String word:string(token).trim().split("\\s+"))if(!word.isEmpty())words.add(word);next();}expect(")");words.sort(Comparator.comparingInt(String::length).reversed());value=new Regex("\\b(?:"+String.join("|",words.stream().map(Pattern::quote).toList())+")\\b");}
                    else throw error("不支持的函数 "+value);}
                if(is("=")){next();expect(">");try{value=new Export(Integer.parseInt(string(value)),string(token));}catch(NumberFormatException e){throw error("export 组号无效");}next();}
                else if(is(">")){next();value=new Inherit(string(value),string(token));next();}
            }
            if(is("+")){next();value=new Join(value,value());}depth--;return value;
        }
    }
}

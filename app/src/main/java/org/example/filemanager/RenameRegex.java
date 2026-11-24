package org.example.filemanager;

import java.io.IOException;

/** Fast linear matching where possible; bounded Java-style matching for lookarounds/backreferences. */
final class RenameRegex {
    private static final int MAX_OUTPUT = 4096;
    private static final int MAX_INPUT = 65536;
    private static final int MAX_READS = 500_000;
    private static final long MAX_NANOS = 150_000_000L;
    private static final String LIMIT_MESSAGE = "正则匹配超过计算预算，请简化表达式；本次预览不会执行任何改名";
    private final com.google.re2j.Pattern linear;
    private final com.florianingerl.util.regex.Pattern advanced;
    private volatile boolean exhausted;

    RenameRegex(String expression) {
        com.google.re2j.Pattern fast = null;
        com.florianingerl.util.regex.Pattern compatible;
        try {
            compatible = com.florianingerl.util.regex.Pattern.compile(expression);
            try { fast = com.google.re2j.Pattern.compile(expression); }
            catch (com.google.re2j.PatternSyntaxException unsupported) { /* The bounded engine supports these constructs. */ }
        } catch (com.florianingerl.util.regex.PatternSyntaxException invalid) {
            throw new IllegalArgumentException("正则表达式无效：" + invalid.getDescription(), invalid);
        } catch (StackOverflowError tooComplex) {
            throw new IllegalArgumentException("正则表达式嵌套过深，请减少分组层级", tooComplex);
        }
        // Named replacement groups use the same Java-style syntax on every Android version.
        linear = expression.contains("(?<") ? null : fast;
        if (linear == null) checkRepetitions(expression);
        advanced = compatible;
    }

    static void checkRepetitions(String expression) {
        // This engine's possessive empty-group loop does not read the input. Such loops cannot
        // be interrupted by CharSequence checks, so reject them before entering the matcher.
        String syntax=structuralText(expression);
        if(syntax.contains("*+")||syntax.contains("++"))
            throw new IllegalArgumentException("当前改名正则不接受无限占有量词 *+ 或 ++，请使用原子分组 (?>...) 控制回溯");
        java.util.regex.Matcher counts=java.util.regex.Pattern.compile("\\{([0-9]+)(,([0-9]*))?\\}(\\+)?").matcher(syntax);
        long work=1;
        while(counts.find()){
            boolean unbounded=counts.group(2)!=null&&counts.group(3).isEmpty();
            if(unbounded&&counts.group(4)!=null)
                throw new IllegalArgumentException("当前改名正则不接受无限占有量词 {n,}+，请使用原子分组 (?>...)");
            String value=counts.group(2)==null||unbounded?counts.group(1):counts.group(3);
            long count;try{count=Long.parseLong(value);}catch(NumberFormatException huge){count=Long.MAX_VALUE;}
            if(count>4096||count>0&&work>65536/count)
                throw new IllegalArgumentException("正则重复次数过大，请减少量词上限或嵌套次数");
            work*=Math.max(1,count);
        }
    }

    /** Remove literals/comments only for the repetition guard, without changing the actual regex. */
    private static String structuralText(String expression) {
        StringBuilder out=new StringBuilder();java.util.ArrayDeque<Boolean> scopes=new java.util.ArrayDeque<>();
        boolean comments=false;int classes=0;
        for(int n=0;n<expression.length();n++){
            char c=expression.charAt(n);
            if(c=='\\'){
                if(n+1<expression.length()&&expression.charAt(n+1)=='Q'){
                    int end=expression.indexOf("\\E",n+2);n=end<0?expression.length():end+1;
                }else n++;
                out.append('a');continue;
            }
            if(comments&&c=='#'){while(n+1<expression.length()&&expression.charAt(n+1)!='\n'&&expression.charAt(n+1)!='\r')n++;continue;}
            if(comments&&Character.isWhitespace(c))continue;
            if(c=='['){classes++;out.append('a');continue;}
            if(c==']'&&classes>0){classes--;continue;}
            if(classes>0)continue;
            if(c=='('){
                int end=n+2;boolean changed=comments,minus=false,flags=false;
                if(n+1<expression.length()&&expression.charAt(n+1)=='?'){
                    while(end<expression.length()){
                        char flag=expression.charAt(end);
                        if(flag=='-'){minus=true;end++;continue;}
                        if("idmsuxU".indexOf(flag)<0)break;
                        flags=true;if(flag=='x')changed=!minus;end++;
                    }
                }
                if(flags&&end<expression.length()&&(expression.charAt(end)==':'||expression.charAt(end)==')')){
                    if(expression.charAt(end)==':'){scopes.push(comments);out.append('(');}
                    comments=changed;n=end;continue;
                }
                scopes.push(comments);
            }else if(c==')'&&!scopes.isEmpty())comments=scopes.pop();
            out.append(c);
        }
        return out.toString();
    }

    String replace(String source, String replacement) throws IOException {
        Tasks.Progress.check();
        if (exhausted) throw new IOException(LIMIT_MESSAGE);
        if (source.length() > MAX_INPUT) throw new IOException("待替换的文件名表达式过长");
        Budget budget = new Budget();
        Limited input = new Limited(source, 0, source.length(), budget);
        StringBuilder result = new StringBuilder(Math.min(source.length() + 32, MAX_OUTPUT));
        try {
            int copied=0;
            if (linear != null) {
                com.google.re2j.Matcher matcher = linear.matcher(input);
                while (matcher.find()) {
                    budget.check();append(result,source,copied,matcher.start());
                    replacement(result,replacement,source,matcher.groupCount(),matcher::start,matcher::end,matcher::start,matcher::end);
                    copied=matcher.end();
                }
            } else {
                com.florianingerl.util.regex.Matcher matcher = advanced.matcher(input);
                while (matcher.find()) {
                    budget.check();append(result,source,copied,matcher.start());
                    replacement(result,replacement,source,matcher.groupCount(),matcher::start,matcher::end,matcher::start,matcher::end);
                    copied=matcher.end();
                }
            }
            append(result,source,copied,source.length());budget.check();return result.toString();
        } catch (Stopped stopped) {
            if (stopped.cancelled) throw new Tasks.CancelledException();
            exhausted=true;throw new IOException(LIMIT_MESSAGE, stopped);
        } catch (StackOverflowError tooComplex) {
            exhausted=true;throw new IOException("正则匹配嵌套过深，请简化表达式；本次预览不会执行任何改名", tooComplex);
        } catch (RuntimeException invalid) {
            throw new IOException("查找替换失败：" + invalid.getMessage(), invalid);
        }
    }

    private static void append(StringBuilder output,String text,int start,int end)throws IOException {
        if(start<0)return; // An optional capture that did not participate expands to an empty string.
        if(end-start>MAX_OUTPUT-output.length())throw new IOException("生成的文件名过长");
        output.append(text,start,end);
    }

    private static void replacement(StringBuilder output,String replacement,String source,int groups,
                                    java.util.function.IntUnaryOperator start,java.util.function.IntUnaryOperator end,
                                    java.util.function.ToIntFunction<String> namedStart,java.util.function.ToIntFunction<String> namedEnd)throws IOException {
        for(int cursor=0;cursor<replacement.length();){
            char c=replacement.charAt(cursor++);
            if(c=='\\'){
                if(cursor==replacement.length())throw new IllegalArgumentException("替换内容不能以单独的反斜杠结尾");
                append(output,replacement,cursor,cursor+1);cursor++;continue;
            }
            if(c!='$'){append(output,replacement,cursor-1,cursor);continue;}
            if(cursor==replacement.length())throw new IllegalArgumentException("$ 后需要捕获组编号或 {组名}");
            if(replacement.charAt(cursor)=='{'){
                int closing=replacement.indexOf('}',cursor+1);
                if(closing<0)throw new IllegalArgumentException("命名捕获组缺少 }");
                String name=replacement.substring(cursor+1,closing);
                if(name.isEmpty())throw new IllegalArgumentException("命名捕获组名称不能为空");
                append(output,source,namedStart.applyAsInt(name),namedEnd.applyAsInt(name));cursor=closing+1;continue;
            }
            int group=replacement.charAt(cursor++)-'0';
            if(group<0||group>9||group>groups)throw new IllegalArgumentException("替换内容引用了不存在的捕获组");
            while(cursor<replacement.length()){
                int digit=replacement.charAt(cursor)-'0';if(digit<0||digit>9)break;
                int candidate=group*10+digit;if(candidate>groups)break;group=candidate;cursor++;
            }
            append(output,source,start.applyAsInt(group),end.applyAsInt(group));
        }
    }

    private static final class Stopped extends RuntimeException {
        final boolean cancelled;
        Stopped(boolean cancelled) { super(null, null, false, false);this.cancelled=cancelled; }
    }
    private static final class Budget {
        final long started=System.nanoTime();
        int reads;
        void read() { if (++reads > MAX_READS) throw new Stopped(false);if ((reads & 63) == 0) check(); }
        void check() {
            try { Tasks.Progress.check(); } catch (Tasks.CancelledException cancelled) { throw new Stopped(true); }
            if (System.nanoTime()-started > MAX_NANOS) throw new Stopped(false);
        }
    }
    private static final class Limited implements CharSequence {
        final String value;final int from,to;final Budget budget;
        Limited(String value,int from,int to,Budget budget){this.value=value;this.from=from;this.to=to;this.budget=budget;}
        @Override public int length(){budget.read();return to-from;}
        @Override public char charAt(int index){budget.read();if(index<0||index>=to-from)throw new IndexOutOfBoundsException();return value.charAt(from+index);}
        @Override public CharSequence subSequence(int start,int end){budget.check();if(start<0||end<start||end>to-from)throw new IndexOutOfBoundsException();return new Limited(value,from+start,from+end,budget);}
        @Override public String toString(){budget.check();return value.substring(from,to);}
    }
}

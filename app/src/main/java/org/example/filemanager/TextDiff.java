package org.example.filemanager;

import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Patience anchors with bounded LCS in small gaps. Equal rows always have equal normalized text. */
final class TextDiff {
    enum Kind { SAME, ADD, REMOVE, CHANGE }
    record Row(int leftLine, String left, int rightLine, String right, Kind kind) {}
    private record Edit(int a, int b) {}
    private record Range(int a, int ae, int b, int be) {}
    static List<Row> compare(String a, String b, boolean spaces, boolean ignoreCase, BooleanSupplier cancelled) throws IOException {
        String[] left = lines(a), right = lines(b); if (left.length + right.length > 120000) throw new IOException("文本超过 120000 行，请分段对比");
        String[] l = normalize(left, spaces, ignoreCase), r = normalize(right, spaces, ignoreCase);
        List<Edit> edits = new ArrayList<>(); ArrayDeque<Range> work = new ArrayDeque<>(); work.push(new Range(0,l.length,0,r.length));
        while(!work.isEmpty()) {
            check(cancelled); Range range = work.pop(); int x=range.a,y=range.b,xe=range.ae,ye=range.be;
            while(x<xe&&y<ye&&l[x].equals(r[y])) edits.add(new Edit(x++,y++));
            if(x==xe) { while(y<ye)edits.add(new Edit(-1,y++));continue; }
            if(y==ye) { while(x<xe)edits.add(new Edit(x++,-1));continue; }
            int suffix=0;while(x<xe-suffix&&y<ye-suffix&&l[xe-suffix-1].equals(r[ye-suffix-1]))suffix++;
            if(suffix>0) { work.push(new Range(xe-suffix,xe,ye-suffix,ye));xe-=suffix;ye-=suffix; }
            if(x==xe||y==ye) {work.push(new Range(x,xe,y,ye));continue;}
            if((long)(xe-x+1)*(ye-y+1)<=300000) {
                int[][] table=new int[xe-x+1][ye-y+1];
                for(int i=xe-x-1;i>=0;i--) {check(cancelled);for(int j=ye-y-1;j>=0;j--)table[i][j]=l[x+i].equals(r[y+j])?1+table[i+1][j+1]:Math.max(table[i+1][j],table[i][j+1]);}
                int i=0,j=0;while(i<xe-x||j<ye-y) {
                    if(i<xe-x&&j<ye-y&&l[x+i].equals(r[y+j]))edits.add(new Edit(x+i++,y+j++));
                    else if(i<xe-x&&(j==ye-y||table[i+1][j]>=table[i][j+1]))edits.add(new Edit(x+i++,-1));else edits.add(new Edit(-1,y+j++));
                }continue;
            }
            Map<String,Integer> lm=unique(l,x,xe),rm=unique(r,y,ye);List<Edit> candidates=new ArrayList<>();
            for(int i=x;i<xe;i++) {Integer pos=rm.get(l[i]);if(lm.get(l[i])==i&&pos!=null&&pos>=0)candidates.add(new Edit(i,pos));}
            List<Edit> anchors=lis(candidates);
            if(anchors.isEmpty()) {while(x<xe)edits.add(new Edit(x++,-1));while(y<ye)edits.add(new Edit(-1,y++));}
            else {
                List<Range> chunks=new ArrayList<>();for(Edit anchor:anchors) {chunks.add(new Range(x,anchor.a,y,anchor.b));chunks.add(new Range(anchor.a,anchor.a+1,anchor.b,anchor.b+1));x=anchor.a+1;y=anchor.b+1;}
                chunks.add(new Range(x,xe,y,ye));for(int i=chunks.size()-1;i>=0;i--)work.push(chunks.get(i));
            }
        }
        List<Row> out=new ArrayList<>();
        for(int i=0;i<edits.size();) {
            Edit e=edits.get(i);if(e.a>=0&&e.b>=0) {out.add(new Row(e.a+1,left[e.a],e.b+1,right[e.b],Kind.SAME));i++;continue;}
            List<Integer> removed=new ArrayList<>(),added=new ArrayList<>();
            while(i<edits.size()) {e=edits.get(i);if(e.a>=0&&e.b>=0)break;if(e.a>=0)removed.add(e.a);else added.add(e.b);i++;}
            for(int n=0;n<Math.max(removed.size(),added.size());n++) {
                int ai=n<removed.size()?removed.get(n):-1,bi=n<added.size()?added.get(n):-1;
                out.add(new Row(ai<0?0:ai+1,ai<0?"":left[ai],bi<0?0:bi+1,bi<0?"":right[bi],ai<0?Kind.ADD:bi<0?Kind.REMOVE:Kind.CHANGE));
            }
        }return List.copyOf(out);
    }
    private static String[] lines(String text) { return text.isEmpty()?new String[0]:text.replace("\r\n","\n").replace('\r','\n').split("\n",-1); }
    private static String[] normalize(String[] input,boolean spaces,boolean ignoreCase) {
        String[] output=new String[input.length];for(int i=0;i<input.length;i++) {String text=input[i];if(spaces)text=text.replaceAll("\\s+", "");if(ignoreCase)text=text.toLowerCase(Locale.ROOT);output[i]=text;}return output;
    }
    private static Map<String,Integer> unique(String[] text,int from,int end) {Map<String,Integer> out=new HashMap<>();for(int i=from;i<end;i++)out.put(text[i],out.containsKey(text[i])?-1:i);return out;}
    private static List<Edit> lis(List<Edit> input) {
        int[] tail=new int[input.size()],prev=new int[input.size()];int size=0;
        for(int i=0;i<input.size();i++) {int lo=0,hi=size;while(lo<hi){int m=(lo+hi)>>>1;if(input.get(tail[m]).b<input.get(i).b)lo=m+1;else hi=m;}prev[i]=lo==0?-1:tail[lo-1];tail[lo]=i;if(lo==size)size++;}
        List<Edit> result=new ArrayList<>();for(int i=size==0?-1:tail[size-1];i>=0;i=prev[i])result.add(input.get(i));Collections.reverse(result);return result;
    }
    static void check(BooleanSupplier cancelled) throws IOException {if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new InterruptedIOException("已停止对比");}
    private TextDiff() {}
}

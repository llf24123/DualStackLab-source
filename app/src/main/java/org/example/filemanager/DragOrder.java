package org.example.filemanager;

import android.content.ClipData;
import android.view.*;
import android.widget.ListView;
import java.util.function.BiConsumer;

final class DragOrder {
    private static final class Moving { final ListView owner; int position; Moving(ListView owner,int position){this.owner=owner;this.position=position;} }
    static void install(ListView list,BiConsumer<Integer,Integer> moved){
        list.setOnItemLongClickListener((p,view,position,id)->view.startDragAndDrop(ClipData.newPlainText("排序",""),new View.DragShadowBuilder(view),new Moving(list,position),0));
        list.setOnDragListener((v,event)->{
            if(!(event.getLocalState() instanceof Moving move)||move.owner!=list)return false;
            switch(event.getAction()){
                case DragEvent.ACTION_DRAG_LOCATION -> {
                    int target=list.pointToPosition((int)event.getX(),(int)event.getY());
                    if(target>=0&&target!=move.position){int from=move.position;move.position=target;moved.accept(from,target);}
                    int edge=Ui.dp(list.getContext(),40);if(event.getY()<edge)list.smoothScrollBy(-edge,Ui.MOTION_SHORT);else if(event.getY()>list.getHeight()-edge)list.smoothScrollBy(edge,Ui.MOTION_SHORT);
                }
                case DragEvent.ACTION_DROP,DragEvent.ACTION_DRAG_ENDED -> { list.invalidateViews(); }
            }return true;
        });
    }
    private DragOrder(){}
}

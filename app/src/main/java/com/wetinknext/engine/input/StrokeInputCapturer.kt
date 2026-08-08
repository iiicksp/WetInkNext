package com.wetinknext.engine.input

import android.view.MotionEvent
import com.wetinknext.engine.core.Camera
import java.util.concurrent.ArrayBlockingQueue

/** Converts View-local MotionEvent samples to canvas coordinates on the UI thread. */
class StrokeInputCapturer(private val camera: Camera, private val pool: InputBatchPool, private val queue: ArrayBlockingQueue<InputBatch>) {
    private var activePointerId = -1
    private val canvasPoint = FloatArray(2)

    /** Не скрываем потери: пул кончился => сэмплы утрачены. */
    var droppedBatches = 0L
        private set

    fun onTouchEvent(event: MotionEvent): Boolean = when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            activePointerId = event.getPointerId(0)
            enqueue(event, InputAction.DOWN, 0, false)
        }
        MotionEvent.ACTION_POINTER_DOWN -> {
            // Штрих уже идёт: второй палец / ладонь НЕ перехватывает управление.
            if (activePointerId >= 0) true else {
                val i = event.actionIndex
                activePointerId = event.getPointerId(i)
                enqueue(event, InputAction.DOWN, i, false)
            }
        }
        MotionEvent.ACTION_MOVE -> {
            val i = event.findPointerIndex(activePointerId)
            i >= 0 && enqueue(event, InputAction.MOVE, i, true)
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
            val i = if (event.actionMasked == MotionEvent.ACTION_UP) 0 else event.actionIndex
            if (event.getPointerId(i) != activePointerId) true else {
                activePointerId = -1
                // historical = true: хвост быстрого штриха больше не теряется.
                enqueue(event, InputAction.UP, i, true)
            }
        }
        MotionEvent.ACTION_CANCEL -> { activePointerId = -1; enqueueEmpty(InputAction.CANCEL) }
        else -> false
    }

    private fun enqueue(event:MotionEvent, action:InputAction, index:Int, historical:Boolean):Boolean {
        val batch = pool.acquire() ?: run { droppedBatches++; return false }
        batch.begin(action);val t=camera.snapshot();val id=event.getPointerId(index);val tool=tool(event,index);if(historical)for(h in 0 until event.historySize){t.screenToCanvas(event.getHistoricalX(index,h),event.getHistoricalY(index,h),canvasPoint);if(!batch.addSample(canvasPoint[0],canvasPoint[1],pressure(event,tool,index,h),0f,0f,orientation(event,tool,index,h),event.getHistoricalEventTime(h)*1_000_000L,id,tool,true))break};t.screenToCanvas(event.getX(index),event.getY(index),canvasPoint);batch.addSample(canvasPoint[0],canvasPoint[1],pressure(event,tool,index,-1),0f,0f,orientation(event,tool,index,-1),event.eventTime*1_000_000L,id,tool,false);if(!queue.offer(batch)){pool.release(batch);droppedBatches++;return false};return true }

    private fun enqueueEmpty(action:InputAction):Boolean {
        val batch = pool.acquire() ?: run { droppedBatches++; return false }
        batch.begin(action);if(!queue.offer(batch)){pool.release(batch);droppedBatches++;return false};return true }
    private fun tool(e:MotionEvent,i:Int)=when(e.getToolType(i)){MotionEvent.TOOL_TYPE_STYLUS->PointerTool.STYLUS;MotionEvent.TOOL_TYPE_ERASER->PointerTool.ERASER;MotionEvent.TOOL_TYPE_FINGER->PointerTool.FINGER;else->PointerTool.UNKNOWN}
    private fun pressure(e:MotionEvent,t:PointerTool,i:Int,h:Int):Float=if(t==PointerTool.FINGER||t==PointerTool.UNKNOWN).65f else (if(h>=0)e.getHistoricalPressure(i,h) else e.getPressure(i)).coerceIn(0f,1f)
    private fun orientation(e:MotionEvent,t:PointerTool,i:Int,h:Int):Float=if(t==PointerTool.FINGER||t==PointerTool.UNKNOWN)0f else if(h>=0)e.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION,i,h) else e.getAxisValue(MotionEvent.AXIS_ORIENTATION,i)
}

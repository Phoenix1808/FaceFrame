package com.example.faceframe.model

//sealed class — Idle / Extracting(pct) / Detecting(pct) / Clustering / Done(persons) / Error(msg)

sealed class ProcessingState {
    data object Idle : ProcessingState()

    data class Analyzing(
        val framesDone : Int, val framesTotal : Int, val facesFound : Int,
    ): ProcessingState(){
        val percent : Int
            get() = if (framesTotal <= 0) 0
        else (framesDone * 100 / framesTotal).coerceIn(0,100)
    }

    data object Grouping : ProcessingState()
    data object BuildingCollage : ProcessingState()

    data class Done(
        val people : List<Person>,
        val collagePath : String,
    ) : ProcessingState()

    data class Failed(
        val msg : String
    ): ProcessingState()
}
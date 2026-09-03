package com.example.faceframe.model

import android.graphics.Bitmap

//single cluster equals single person
//contains all of it's facesample, apperarnceCount , List<Segmnet>, bestSample

data class Segment(
    val startMs : Long,
    val endMs : Long, val frameCount : Int
){
    val durMs: Long get() = endMs - startMs
}

data class Person(
    val id : Int,
    val samples : List<FaceSample>,
    val segments: List<Segment>,
    val bestSample : FaceSample,
    val representativeShot : Bitmap? = null
){
    val appearanceCount: Int get() = segments.size

    val label: String get() = "Person $id"
}
package com.example.faceframe.model

import android.graphics.Bitmap

/** One continuous stretch where a person is visible. */

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
package com.example.studywebrtc.webrtc

import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy

@Target(
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER
)
@Retention(
    RetentionPolicy.CLASS
)
annotation class CalledByNative( /*
   *  If present, tells which inner class the method belongs to.
   */
    val value: String = ""
)
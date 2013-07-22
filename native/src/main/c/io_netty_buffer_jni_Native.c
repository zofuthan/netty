/*
 * Copyright 2013 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
#include <jni.h>
#include <stdlib.h>
#include "io_netty_buffer_jni_Native.h"


JNIEXPORT void JNICALL Java_io_netty_buffer_jni_Native_freeDirectBuffer(JNIEnv *env, jclass clazz, jobject buf) {
  void *bufAddress = (*env)->GetDirectBufferAddress(env, buf);

  free(bufAddress);
}

JNIEXPORT jobject JNICALL Java_io_netty_buffer_jni_Native_allocateDirectBuffer(JNIEnv *env, jclass clazz, jint size) {
  void *mem = malloc(size);
  jobject directBuffer = (*env)->NewDirectByteBuffer(env, mem, size);
  return directBuffer;
}
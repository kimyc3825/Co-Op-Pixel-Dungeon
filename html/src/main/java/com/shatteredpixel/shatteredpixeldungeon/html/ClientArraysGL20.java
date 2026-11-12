/*
 * Pixel Dungeon
 * Copyright (C) 2012-2015 Oleg Dolya
 *
 * Shattered Pixel Dungeon
 * Copyright (C) 2014-2026 Evan Debenham
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

package com.shatteredpixel.shatteredpixeldungeon.html;

import com.github.xpenatan.gdx.teavm.backends.web.WebGL20;
import com.github.xpenatan.gdx.teavm.backends.web.gl.WebGLRenderingContextExt;

import java.nio.Buffer;

/**
 * WebGL has no client-side vertex arrays, but Shattered (NoosaScript.drawQuad/drawQuadSet/drawElements,
 * and libGDX's SpriteBatch in the VertexArray mode that TextInput forces) passes vertex and index data
 * straight from NIO buffers, which desktop OpenGL and GLES allow.
 *
 * This emulates them the way Emscripten/the old GWT backend do: when a pointer to a client buffer is given,
 * the data is uploaded to a per-attribute scratch VBO and the attribute is pointed at it; client index
 * buffers are uploaded to a scratch element buffer right before the draw call. Previous buffer bindings
 * are restored so the game's own VBO handling is unaffected.
 */
public class ClientArraysGL20 extends WebGL20 {

	private static final int MAX_ATTRIBS = 16;

	private final int[] attribBuffers = new int[MAX_ATTRIBS];
	private int indexBuffer;

	private int boundArrayBuffer;
	private int boundElementBuffer;

	public ClientArraysGL20(WebGLRenderingContextExt gl) {
		super(gl);
	}

	@Override
	public void glBindBuffer(int target, int buffer) {
		if (target == GL_ARRAY_BUFFER) {
			boundArrayBuffer = buffer;
		} else if (target == GL_ELEMENT_ARRAY_BUFFER) {
			boundElementBuffer = buffer;
		}
		super.glBindBuffer(target, buffer);
	}

	@Override
	public void glDeleteBuffer(int buffer) {
		if (buffer == boundArrayBuffer) boundArrayBuffer = 0;
		if (buffer == boundElementBuffer) boundElementBuffer = 0;
		super.glDeleteBuffer(buffer);
	}

	@Override
	public void glVertexAttribPointer(int indx, int size, int type, boolean normalized, int stride, Buffer ptr) {
		if (attribBuffers[indx] == 0) {
			attribBuffers[indx] = glGenBuffer();
		}
		int previous = boundArrayBuffer;
		super.glBindBuffer(GL_ARRAY_BUFFER, attribBuffers[indx]);
		//uploads from the buffer's position to its limit, which is where the pointer starts
		super.glBufferData(GL_ARRAY_BUFFER, ptr.remaining() * elementSize(ptr), ptr, GL_STREAM_DRAW);
		super.glVertexAttribPointer(indx, size, type, normalized, stride, 0);
		super.glBindBuffer(GL_ARRAY_BUFFER, previous);
	}

	@Override
	public void glDrawElements(int mode, int count, int type, Buffer indices) {
		if (indexBuffer == 0) {
			indexBuffer = glGenBuffer();
		}
		int previous = boundElementBuffer;
		super.glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
		super.glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices.remaining() * elementSize(indices), indices, GL_STREAM_DRAW);
		super.glDrawElements(mode, count, type, 0);
		super.glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, previous);
	}

	private static int elementSize(Buffer b) {
		if (b instanceof java.nio.FloatBuffer || b instanceof java.nio.IntBuffer) return 4;
		if (b instanceof java.nio.ShortBuffer || b instanceof java.nio.CharBuffer) return 2;
		return 1;
	}
}

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

package com.watabou.noosa;

import java.util.ArrayList;

//test tooling for html.AutoTest: lists a group's children, which are protected in Group
public class WebGroupAccess {

	public static <T> T findFirst( Group group, Class<T> type ){
		for (Gizmo g : new ArrayList<>(group.members)){
			if (g != null && g.exists && g.visible && type.isInstance(g)) return type.cast(g);
			if (g instanceof Group){
				T found = findFirst((Group) g, type);
				if (found != null) return found;
			}
		}
		return null;
	}
}

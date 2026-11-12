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

package com.shatteredpixel.shatteredpixeldungeon.html.compiler;

import org.teavm.classlib.ReflectionContext;
import org.teavm.classlib.ReflectionSupplier;
import org.teavm.dependency.AbstractDependencyListener;
import org.teavm.dependency.ClassDependency;
import org.teavm.dependency.DependencyAgent;
import org.teavm.dependency.MethodDependency;
import org.teavm.model.MethodDescriptor;
import org.teavm.model.MethodReference;
import org.teavm.model.ValueType;
import org.teavm.vm.spi.TeaVMHost;
import org.teavm.vm.spi.TeaVMPlugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * BUILD-TIME ONLY (runs inside the TeaVM compiler, never compiled to JavaScript).
 *
 * The game creates objects from class names (Bundle, i.e. saves) and from Class objects
 * (Reflection.newInstance). On TeaVM that only works for classes that are "found by name" and whose
 * no-arg constructor is kept. The list of such classes is generated from the compiled game code by
 * the generateReflectionList task (html/build.gradle) and packaged as a resource.
 *
 * gdx-teavm's own reflection support exposes every field and method of each listed class,
 * which for ~1400 game classes makes the compiler run out of memory and bloats the output,
 * so only what Shattered actually uses (Class.forName + no-arg constructors) is exposed here.
 */
public class SPDReflection implements ReflectionSupplier, TeaVMPlugin {

	public static final String LIST_RESOURCE = "spd-reflection-classes.txt";

	private static Set<String> classes;

	private static synchronized Set<String> classes(){
		if (classes == null){
			Set<String> result = new LinkedHashSet<>();
			try (InputStream in = SPDReflection.class.getClassLoader().getResourceAsStream(LIST_RESOURCE)){
				if (in == null){
					throw new IllegalStateException(LIST_RESOURCE + " is missing, run :html:generateReflectionList");
				}
				BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
				String line;
				while ((line = reader.readLine()) != null){
					line = line.trim();
					if (!line.isEmpty()) result.add(line);
				}
			} catch (IOException e){
				throw new IllegalStateException(e);
			}
			classes = Collections.unmodifiableSet(result);
		}
		return classes;
	}

	private static final MethodDescriptor NO_ARG_CONSTRUCTOR = new MethodDescriptor("<init>", ValueType.VOID);

	// ---- ReflectionSupplier ----

	@Override
	public boolean isClassFoundByName(ReflectionContext context, String name) {
		return classes().contains(name);
	}

	@Override
	public Collection<MethodDescriptor> getAccessibleMethods(ReflectionContext context, String className) {
		if (classes().contains(className)){
			return Collections.singletonList(NO_ARG_CONSTRUCTOR);
		}
		return Collections.emptyList();
	}

	// ---- TeaVMPlugin: make sure every listed class and its constructor are part of the build ----

	@Override
	public void install(TeaVMHost host) {
		host.add(new AbstractDependencyListener() {
			@Override
			public void started(DependencyAgent agent) {
				for (String name : classes()){
					ClassDependency cls = agent.linkClass(name);
					if (cls.isMissing()) continue;
					cls.initClass(null);
					MethodDependency ctor = agent.linkMethod(new MethodReference(name, NO_ARG_CONSTRUCTOR));
					if (!ctor.isMissing()){
						ctor.getVariable(0).propagate(agent.getType(ValueType.object(name)));
						ctor.use();
					}
				}
			}
		});
	}
}

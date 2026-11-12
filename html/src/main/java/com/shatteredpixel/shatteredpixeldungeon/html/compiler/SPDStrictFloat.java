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

import org.teavm.model.BasicBlock;
import org.teavm.model.ClassHolder;
import org.teavm.model.ClassHolderTransformer;
import org.teavm.model.ClassHolderTransformerContext;
import org.teavm.model.Instruction;
import org.teavm.model.MethodHolder;
import org.teavm.model.MethodReference;
import org.teavm.model.Program;
import org.teavm.model.ValueType;
import org.teavm.model.Variable;
import org.teavm.model.instructions.BinaryInstruction;
import org.teavm.model.instructions.CastNumberInstruction;
import org.teavm.model.instructions.InvocationType;
import org.teavm.model.instructions.InvokeInstruction;
import org.teavm.model.instructions.NumericOperandType;
import org.teavm.vm.spi.TeaVMHost;
import org.teavm.vm.spi.TeaVMPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * BUILD-TIME ONLY (runs inside the TeaVM compiler, never compiled to JavaScript).
 *
 * TeaVM's JavaScript backend represents float as a JS number (a double) and does not round intermediate
 * results to 32 bits. The JVM does, so for example (int)(0.7f * 10) is 7 on desktop and 6 in the browser.
 * Shattered uses float math all over its game logic (damage, chances, scaling), so to behave 1:1 every
 * float arithmetic result and every conversion to float in the game's own code is followed by
 * Math.fround (html.StrictFloat.round), which is exactly the JVM's float semantics.
 *
 * Only the game's packages are transformed; libGDX rendering math does not affect gameplay.
 */
public class SPDStrictFloat implements TeaVMPlugin, ClassHolderTransformer {

	private static final String[] PACKAGES = {
			"com.shatteredpixel.shatteredpixeldungeon.",
			"com.watabou."
	};

	private static final String HELPER = "com.shatteredpixel.shatteredpixeldungeon.html.StrictFloat";
	private static final MethodReference ROUND = new MethodReference(HELPER, "round", ValueType.FLOAT, ValueType.FLOAT);

	@Override
	public void install(TeaVMHost host) {
		host.add(this);
	}

	@Override
	public void transformClass(ClassHolder cls, ClassHolderTransformerContext context) {
		String name = cls.getName();
		if (name.equals(HELPER) || !inScope(name)) {
			return;
		}
		for (MethodHolder method : cls.getMethods()) {
			Program program = method.getProgram();
			if (program != null) {
				transform(program);
			}
		}
	}

	private static boolean inScope(String className) {
		for (String p : PACKAGES) {
			if (className.startsWith(p)) return true;
		}
		return false;
	}

	private static void transform(Program program) {
		for (int b = 0; b < program.basicBlockCount(); b++) {
			BasicBlock block = program.basicBlockAt(b);
			List<Instruction> targets = new ArrayList<>();
			for (Instruction insn : block) {
				if (insn instanceof BinaryInstruction
						&& ((BinaryInstruction) insn).getOperandType() == NumericOperandType.FLOAT) {
					switch (((BinaryInstruction) insn).getOperation()) {
						case ADD:
						case SUBTRACT:
						case MULTIPLY:
						case DIVIDE:
						case MODULO:
							targets.add(insn);
							break;
						default:
							//comparisons produce ints, bitwise ops don't exist for float
					}
				} else if (insn instanceof CastNumberInstruction
						&& ((CastNumberInstruction) insn).getTargetType() == NumericOperandType.FLOAT
						&& ((CastNumberInstruction) insn).getSourceType() != NumericOperandType.FLOAT) {
					targets.add(insn);
				}
			}
			for (Instruction insn : targets) {
				Variable result;
				Variable temp = program.createVariable();
				if (insn instanceof BinaryInstruction) {
					BinaryInstruction bin = (BinaryInstruction) insn;
					result = bin.getReceiver();
					if (result == null) continue;
					bin.setReceiver(temp);
				} else {
					CastNumberInstruction cast = (CastNumberInstruction) insn;
					result = cast.getReceiver();
					if (result == null) continue;
					cast.setReceiver(temp);
				}
				InvokeInstruction round = new InvokeInstruction();
				round.setType(InvocationType.SPECIAL);
				round.setMethod(ROUND);
				round.setArguments(temp);
				round.setReceiver(result);
				round.setLocation(insn.getLocation());
				insn.insertNext(round);
			}
		}
	}
}

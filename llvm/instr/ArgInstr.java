package llvm.instr;

import llvm.Builder;
import llvm.IRType;
import llvm.ValueType;
import mips.IInstr;
import mips.LswInstr;
import mips.MipsBuilder;
import mips.Register;

/**
 * Materialize a function argument into an SSA value.
 *
 * This avoids eagerly storing parameters into stack slots and re-loading them on every use.
 * The register allocator can keep this value in a register; if spilled, it gets a normal stack slot.
 */
public class ArgInstr extends Instruction {
    // 0-based parameter index in the function signature.
    private final int index;

    public ArgInstr(IRType type, int index) {
        super(ValueType.ARGUMENT, type, Builder.getVarName());
        this.index = index;
    }

    @Override
    public int getSpace() {
        // One word if spilled.
        return 4;
    }

    @Override
    public boolean isPinned() {
        // Must stay at function entry: reads incoming arg regs / caller stack layout.
        return true;
    }

    @Override
    public void toMips() {
        // First four args in $a0-$a3.
        if (index < 4) {
            Register src = (index == 0) ? Register.A0 : (index == 1) ? Register.A1 : (index == 2) ? Register.A2 : Register.A3;
            pushToMem(src);
            return;
        }

        // Stack-passed args: load from caller stack.
        // Caller arg area starts at (current $sp + curFunc.stackSpace).
        int offsetFromEntrySp = MipsBuilder.curFunc.getStackSpace() + 4 * (index - 4);
        Register addr = Register.K0;
        Register val = Register.K1;
        new IInstr("addi", addr, Register.SP, offsetFromEntrySp);
        new LswInstr("lw", val, addr, 0);
        pushToMem(val);
    }
}

package llvm.instr;

import llvm.Builder;
import llvm.IRType;
import llvm.ReturnType;
import llvm.Value;
import llvm.ValueType;
import llvm.constant.ConstantInt;
import mips.Register;

public class MoveInstr extends Instruction {
    public MoveInstr(Value from, Value to) {
        super(ValueType.MOVE_INST, new IRType("void"), "void");
        addUseValue(from);
        addUseValue(to);
    }

    public void print() {
        Value from = getUseValue(0);
        Value to = getUseValue(1);
        System.out.println("move "+from.getName()+" to "+to.getName());
    }

    @Override
    public void toMips() {
        Value from = getUseValue(0);
        Value to = getUseValue(1);
        // Fast-paths to avoid generating redundant moves/loads.
        // Note: In this IR, "move" is semantically an assignment into the destination value.
        Register scratch = tmp(0);
        Register src = valueOrLoad(from, scratch);

        if (to instanceof Instruction) {
            Instruction dst = (Instruction) to;

            // If destination is allocated to a register and the source is already there, do nothing.
            if (!dst.isSpilled() && dst.getAssignedRegister() != null) {
                Register dstReg = dst.getAssignedRegister();
                if (src == dstReg) {
                    return;
                }
                new mips.fake.MoveInstr(src, dstReg);
                return;
            }

            // Destination is spilled: store directly from the source reg (no extra tmp move).
            pushToMem(src, dst);
            return;
        }

        // Fallback: destination is not an Instruction (should be rare); use existing helper.
        loadToReg(from, scratch);
        pushToMem(scratch);
    }

}

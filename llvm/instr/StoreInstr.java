package llvm.instr;

import llvm.BasicBlock;
import llvm.Builder;
import llvm.GlobalVariable;
import llvm.IRType;
import llvm.ReturnType;
import llvm.Use;
import llvm.User;
import llvm.Value;
import llvm.ValueType;
import llvm.constant.ConstantInt;
import mips.IInstr;
import mips.LswInstr;
import mips.MipsBuilder;
import mips.Register;
import optimizer.LLVMOptimizer;

import java.util.ArrayList;

public class StoreInstr extends Instruction {
    public StoreInstr(Value in, Value to) {
        super(ValueType.STORE_INST, new IRType("void"), "void");
        addUseValue(in);
        addUseValue(to);
        Builder.addInstr(this);
    }

    @Override
    public void toMips() {
        Value in = getUseValue(0);
        Value to = getUseValue(1);

        Register t0 = tmp(0);
        Register t1 = tmp(1);
        Register src = valueOrLoad(in, t0);

        // Direct stack slot addressing for constant-index stack arrays:
        //   store v, (gep (alloca [N x i32]), constIdx)
        // => sw src, (-baseMem + constIdx*4)($sp)
        if (to instanceof GepInstr) {
            GepInstr gep = (GepInstr) to;
            Value base = gep.getUseValue(0);
            Value idx = gep.getUseValue(1);
            if (base instanceof AllocaInstr && idx instanceof ConstantInt) {
                int idxVal = Integer.parseInt(idx.getName());
                long off = (long) idxVal * 4L - (long) base.getMemPos();
                if (off >= -32768L && off <= 32767L) {
                    new LswInstr("sw", src, Register.SP, (int) off);
                    return;
                }
            }
        }

        if (to instanceof GlobalVariable) {
            new LswInstr("sw", src, to.getName().substring(1));
        }
        else if (isAddressValue(to)) {
            Register addr = valueOrLoad(to, t1);
            new LswInstr("sw", src, addr, 0);
        }
        else {
            new LswInstr("sw", src, Register.SP, -to.getMemPos());
        }
    }

    public boolean toVar() {
        Value to = getUseValue(1);
        if (to instanceof AllocaInstr && to.getType().ptTo().toString().equals("i32")) {
            return true;
        }
        return false;
    }

    public void store(BasicBlock block) {
        Value to = getUseValue(1);
        Value in = getUseValue(0);
        AllocaInstr allocaInstr = (AllocaInstr) to;
        allocaInstr.store(block, in);
    }

    @Override
    public void print() {
        Value in = (Value) getUseValue(0);
        Value to = (Value) getUseValue(1);
        System.out.println("store "+in.getTypeName()+" "+in.getName()+", "+to.getTypeName()+" "+to.getName());
    }

    @Override
    public boolean isDead() {
        Value to = getUseValue(1);
        if (to instanceof GepInstr) {
            return false;
        }
        else {
            return to.getUserList().size() <= 1;
        }
    }

    @Override
    public boolean isPinned() {
        return true;
    }
}

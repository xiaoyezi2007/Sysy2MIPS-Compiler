package llvm.instr;

import llvm.BasicBlock;
import llvm.Builder;
import llvm.GlobalValue;
import llvm.GlobalVariable;
import llvm.ReturnType;
import llvm.User;
import llvm.Value;
import llvm.ValueType;
import llvm.constant.Constant;
import llvm.constant.ConstantInt;
import mips.IInstr;
import mips.LswInstr;
import mips.MipsBuilder;
import mips.Register;

import java.util.ArrayList;

public class LoadInstr extends Instruction {
    public LoadInstr(Value from) {
        super(ValueType.LOAD_INST, from.getType().ptTo(), Builder.getVarName());
        addUseValue(from);
        Builder.addInstr(this);
    }

    @Override
    public int getSpace() {
        return 4;
    }

    @Override
    public void toMips() {
        Value from = getUseValue(0);
        Register t0 = tmp(0);
        Register t1 = tmp(1);

        // Direct stack slot addressing for constant-index stack arrays:
        //   load (gep (alloca [N x i32]), constIdx)
        // => lw t0, (-baseMem + constIdx*4)($sp)
        // This avoids generating the GEP address and spilling the pointer.
        if (from instanceof GepInstr) {
            GepInstr gep = (GepInstr) from;
            Value base = gep.getUseValue(0);
            Value idx = gep.getUseValue(1);
            if (base instanceof AllocaInstr && idx instanceof ConstantInt) {
                int idxVal = Integer.parseInt(idx.getName());
                long off = (long) idxVal * 4L - (long) base.getMemPos();
                if (off >= -32768L && off <= 32767L) {
                    new LswInstr("lw", t0, Register.SP, (int) off);
                    pushToMem(t0);
                    return;
                }
            }
        }

        if (from instanceof GlobalVariable) {
            new LswInstr("lw", t0, from.getName().substring(1));
        }
        else if (isAddressValue(from)) {
            Register addr = valueOrLoad(from, t1);
            new LswInstr("lw", t0, addr, 0);
        }
        else {
            new LswInstr("lw", t0, Register.SP, -from.getMemPos());
        }
        pushToMem(t0);
    }

    public boolean fromVar() {
        Value from = getUseValue(0);
        if (from instanceof AllocaInstr && from.getType().ptTo().toString().equals("i32")) {
            return true;
        }
        return false;
    }

    public void load(BasicBlock block) {
        Value from = getUseValue(0);
        AllocaInstr allocaInstr = (AllocaInstr) from;
        Value ans = allocaInstr.load(block);
        for (User user : userList) {
            user.changeUse(this, ans);
        }
        userList = new ArrayList<>();
    }

    @Override
    public void print() {
        Value from = getUseValue(0);
        System.out.println(name+" = load "+getTypeName()+", "+from.getTypeName()+" "+from.getName());
    }

    @Override
    public Constant getValue() {
        Value from = getUseValue(0);
        if (from instanceof GlobalValue) {
            return from.getValue();
        }
        else if (from instanceof AllocaInstr) {
            return from.getValue();
        }
        else if (from instanceof GepInstr) {
            return from.getValue();
        }
        return null;
    }

    @Override
    public boolean isPinned() {
        return true;
    }
}

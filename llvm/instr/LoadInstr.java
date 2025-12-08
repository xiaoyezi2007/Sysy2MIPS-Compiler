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
        if (from instanceof GlobalVariable) {
            new LswInstr("lw", Register.T0, from.getName().substring(1));
        }
        else if (from.getType().isAddr) {
            loadToReg(from, Register.T1);
            new LswInstr("lw", Register.T0, Register.T1, 0);
        }
        else {
            new LswInstr("lw", Register.T0, Register.SP, -from.getMemPos());
        }
        pushToMem(Register.T0);
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

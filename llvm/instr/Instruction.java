package llvm.instr;

import llvm.Builder;
import llvm.GlobalVariable;
import llvm.IRType;
import llvm.ReturnType;
import llvm.Use;
import llvm.User;
import llvm.Value;
import llvm.ValueType;
import llvm.constant.Constant;
import llvm.constant.ConstantInt;
import mips.IInstr;
import mips.JInstr;
import mips.LswInstr;
import mips.MipsBuilder;
import mips.Register;
import mips.fake.LiInstr;

import java.util.ArrayList;

public abstract class Instruction extends User {
    public boolean isPrint = false;
    public boolean isAddr = false;

    public Instruction(ValueType valueType, IRType Type, String name) {
        super(valueType, Type, name);
    }

    public Instruction(ValueType valueType, IRType Type) {
        super(valueType, Type, Builder.getVarName());
    }

    public void print() {

    }

    public Constant getValue() {
        return null;
    }

    public int getSpace() {
        return 0;
    }

    protected void pushToMem(Register reg) {
        //new IInstr("addi", Register.SP, Register.SP, -4);
        memory = MipsBuilder.memory;
        new LswInstr("sw", reg, Register.SP, -memory);
        MipsBuilder.memory -= 4;
    }

    protected void pushToMem(Register reg, Instruction instr) {
        if (instr.memory != 1) {
            new LswInstr("sw", reg, Register.SP, -instr.memory);
        }
        else {
            instr.memory = MipsBuilder.memory;
            new LswInstr("sw", reg, Register.SP, -instr.memory);
            MipsBuilder.memory -= 4;
        }
    }

    protected void loadAddrToReg(Value base, Register reg) {
        new IInstr("addiu", reg, Register.SP, -base.getMemPos());
    }

    protected void loadToReg(Value value, Register to) {
        if (value instanceof ConstantInt) {
            new LiInstr(to, Integer.parseInt(value.getName()));
            return;
        }
        else if (value instanceof GlobalVariable) {
            new LswInstr("lw", to, ((GlobalVariable) value).getName().substring(1));
        }
        new LswInstr("lw", to, Register.SP, -value.getMemPos());
    }

    public boolean isDef(AllocaInstr instr) {
        return this instanceof StoreInstr && getUseValue(1).equals(instr);
    }

    //Optimize
    public boolean isDead() {
        ArrayList<User> userList = getUserList();
        return userList.isEmpty();
    }

    public void setName(String name) {
        this.name = name;
    }
}

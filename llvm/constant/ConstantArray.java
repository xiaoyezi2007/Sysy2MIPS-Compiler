package llvm.constant;

import llvm.IRType;
import llvm.ReturnType;
import llvm.ValueType;

import java.util.ArrayList;

public class ConstantArray extends Constant {
    private int size;
    private ArrayList<Constant> valueList = new ArrayList<>();

    public ConstantArray(ValueType valueType, IRType Type, String name) {
        super(valueType, Type, name);
    }
}

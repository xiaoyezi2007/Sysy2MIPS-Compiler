package llvm;

import java.util.ArrayList;

public class Function extends GlobalValue {
    ArrayList<BasicBlock> basicBlocks = new ArrayList<>();

    public Function(ValueType valueType, ReturnType Type, String name) {
        super(valueType, Type, name);
    }
}

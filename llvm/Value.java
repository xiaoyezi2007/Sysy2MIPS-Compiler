package llvm;

import llvm.constant.Constant;

import java.util.ArrayList;

public class Value {
    private ValueType valueType;
    protected ReturnType Type;
    protected String name;
    protected ArrayList<Use> useList = new ArrayList<>();
    protected ArrayList<User> userList = new ArrayList<>();

    public Value(ValueType valueType, ReturnType Type, String name) {
        this.valueType = valueType;
        this.Type = Type;
        this.name = name;
    }

    public void addUser(User user) {
        userList.add(user);
    }

    public void addUse(Use use) {
        useList.add(use);
    }

    public Value getUseValue(int i) {
        return useList.get(i).getValue();
    }

    public ReturnType getType() {
        return Type;
    }

    public String getName() {
        return name;
    }

    public Constant getValue() {
        return null;
    }

    public void print() {

    }
}

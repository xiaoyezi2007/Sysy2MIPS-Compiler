package llvm;

import llvm.constant.Constant;

import java.util.ArrayList;

public class Value {
    private ValueType valueType;
    public boolean isConst = false;
    protected IRType Type;
    protected String name;
    protected Integer memory = 1;
    protected ArrayList<Use> useList = new ArrayList<>();
    protected ArrayList<User> userList = new ArrayList<>();
    protected ArrayList<Value> useValueList = new ArrayList<>();

    public Value(ValueType valueType, IRType Type, String name) {
        this.valueType = valueType;
        this.Type = Type;
        this.name = name;
    }

    public void setIsConst(boolean isConst ) {
        this.isConst = isConst;
    }

    public ArrayList<Use> getUseList() {
        return useList;
    }

    public ArrayList<User> getUserList() {
        return userList;
    }

    public void addUser(User user) {
        userList.add(user);
    }

    public void rmUser(User user) {
        while (userList.contains(user)) {
            userList.remove(user);
        }
    }

    public void addUse(Use use) {
        useList.add(use);
    }

    public void addToUseValueList(Value value) {
        useValueList.add(value);
    }

    public Value getUseValue(int i) {
        return useList.get(i).getValue();
    }

    public IRType getType() {
        return Type;
    }

    public boolean before(Value v) {
        return this.name.compareTo(v.getName()) < 0;
    }

    public String getTypeName() {
        return Type.toString();
    }

    public String getName() {
        return name;
    }

    public Constant getValue() {
        return null;
    }

    public void toMips() {}

    public void print() {

    }

    public int getMemPos() {
        return memory;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Value) {
            Value v = (Value) obj;
            return this.name.equals(v.getName());
        }
        return false;
    }

    public ArrayList<Value> getUsers() {
        ArrayList<Value> users = new ArrayList<>(userList);
        return users;
    }
}

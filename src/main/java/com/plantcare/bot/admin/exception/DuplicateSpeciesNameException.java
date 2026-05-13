package com.plantcare.bot.admin.exception;

public class DuplicateSpeciesNameException extends RuntimeException {

    private final String fieldName = "name";

    public DuplicateSpeciesNameException(String name) {
        super("Вид с таким именем уже существует: " + name);
    }

    public String getFieldName() {
        return fieldName;
    }
}

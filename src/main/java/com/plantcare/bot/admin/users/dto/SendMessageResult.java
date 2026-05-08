package com.plantcare.bot.admin.users.dto;

public record SendMessageResult(boolean success, String error) {
    public static SendMessageResult ok() { return new SendMessageResult(true, null); }
    public static SendMessageResult fail(String error) {
        return new SendMessageResult(false, error);
    }
}

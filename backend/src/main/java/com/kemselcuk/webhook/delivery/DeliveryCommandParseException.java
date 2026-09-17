package com.kemselcuk.webhook.delivery;

/**
 * Strict command parsing failure with no raw value retained or included in
 * the exception message.
 */
public class DeliveryCommandParseException extends RuntimeException {

    private final DeliveryCommandErrorCategory category;

    public DeliveryCommandParseException(DeliveryCommandErrorCategory category) {
        super(category.name());
        this.category = category;
    }

    public DeliveryCommandErrorCategory category() {
        return category;
    }
}

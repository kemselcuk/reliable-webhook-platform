package com.kemselcuk.webhook.delivery;

/**
 * Small stateless boundary around outbound webhook HTTP.
 */
public interface DeliveryHttpClient {

    DeliveryHttpResult post(DeliveryWorkSnapshot work);
}

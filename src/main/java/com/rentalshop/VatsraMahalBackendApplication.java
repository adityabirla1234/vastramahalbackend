package com.rentalshop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableAsync backs PushSyncService.notifyOtherDevices (FCM push sync) and
// RedundantTrailService.deliverAsync (Layer-3 Telegram/Sheets trail) --
// without it, @Async would be silently ignored and both would run
// synchronously on the caller's thread/transaction instead.
//
// @EnableScheduling backs RedundantTrailService.retryFailedDeliveries, the
// periodic sweep that catches any Layer-3 delivery the post-commit async
// hook missed (app restart mid-flight, Telegram/Sheets down at the time).
@EnableAsync
@EnableScheduling
@SpringBootApplication
public class VatsraMahalBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(VatsraMahalBackendApplication.class, args);
	}

}

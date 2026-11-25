package br.furb.ticketpro.inventory.modules.reservations.application;

import java.security.SecureRandom;

/** Random ticket codes XXXX-XXXX-XXXX, without the look-alikes O/0 and I/1 (RFC 0006 §5.5). */
public final class TicketCodes {

  static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  private static final SecureRandom RANDOM = new SecureRandom();

  private TicketCodes() {}

  public static String next() {
    StringBuilder sb = new StringBuilder(14);
    for (int i = 0; i < 12; i++) {
      if (i > 0 && i % 4 == 0) {
        sb.append('-');
      }
      sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
    }
    return sb.toString();
  }
}

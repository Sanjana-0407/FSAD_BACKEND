package com.edufeedback.service;

import com.edufeedback.model.OtpToken;
import com.edufeedback.repository.OtpTokenRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class OtpService {

    @Autowired
    private OtpTokenRepository otpTokenRepository;

    @Autowired
    private EmailService emailService;

    @Value("${app.otp.expiry-minutes:10}")
    private int otpExpiryMinutes;

    private final SecureRandom random = new SecureRandom();

    @Transactional
    public void generateAndSendOtp(String email, OtpToken.OtpPurpose purpose) {
        String normalizedEmail = email.toLowerCase().trim();
        try {
            otpTokenRepository.deleteByEmailAndPurpose(normalizedEmail, purpose);
        } catch (Exception e) {
            System.err.println("[OtpService] Could not delete old OTPs: " + e.getMessage());
        }

        String otp = String.format("%06d", random.nextInt(1000000));

        OtpToken otpToken = new OtpToken();
        otpToken.setEmail(normalizedEmail);
        otpToken.setOtp(otp);
        otpToken.setPurpose(purpose);
        otpToken.setExpiresAt(LocalDateTime.now().plusMinutes(otpExpiryMinutes));
        otpToken.setUsed(false);
        otpToken.setVerified(false);

        otpTokenRepository.save(otpToken);
        System.out.println("[OtpService] OTP saved for " + normalizedEmail + " purpose=" + purpose);

        emailService.sendOtpEmail(normalizedEmail, otp, purpose.name());
    }

    /**
     * Full verify: marks OTP as used (consumed). Use for email verification and combined OTP+action flows.
     */
    @Transactional
    public boolean verifyOtp(String email, String otp, OtpToken.OtpPurpose purpose) {
        String normalizedEmail = email.toLowerCase().trim();
        String trimmedOtp = otp != null ? otp.trim() : "";

        Optional<OtpToken> tokenOpt = otpTokenRepository
                .findTopByEmailAndPurposeAndUsedFalseOrderByCreatedAtDesc(normalizedEmail, purpose);

        if (tokenOpt.isEmpty()) {
            System.out.println("[OtpService] No unused OTP found for " + normalizedEmail);
            return false;
        }

        OtpToken token = tokenOpt.get();

        if (token.isExpired()) {
            System.out.println("[OtpService] OTP expired for " + normalizedEmail);
            return false;
        }

        String storedOtp = token.getOtp() != null ? token.getOtp().trim() : "";
        if (!storedOtp.equals(trimmedOtp)) {
            System.out.println("[OtpService] OTP mismatch for " + normalizedEmail);
            return false;
        }

        token.setUsed(true);
        token.setVerified(false);
        otpTokenRepository.save(token);
        System.out.println("[OtpService] OTP verified (consumed) for " + normalizedEmail);
        return true;
    }

    /**
     * Step-verify only: marks OTP as used=true AND verified=true, keeping it for a second step.
     * Used for: password reset step 2 (verify OTP), then step 3 (reset password with verified OTP).
     * Also used for admin login step 1 verification.
     */
    @Transactional
    public boolean verifyOtpOnly(String email, String otp, OtpToken.OtpPurpose purpose) {
        String normalizedEmail = email.toLowerCase().trim();
        String trimmedOtp = otp != null ? otp.trim() : "";

        Optional<OtpToken> tokenOpt = otpTokenRepository
                .findTopByEmailAndPurposeAndUsedFalseOrderByCreatedAtDesc(normalizedEmail, purpose);

        if (tokenOpt.isEmpty()) {
            System.out.println("[OtpService] No unused OTP found for " + normalizedEmail);
            return false;
        }

        OtpToken token = tokenOpt.get();

        if (token.isExpired()) {
            System.out.println("[OtpService] OTP expired for " + normalizedEmail);
            return false;
        }

        String storedOtp = token.getOtp() != null ? token.getOtp().trim() : "";
        if (!storedOtp.equals(trimmedOtp)) {
            System.out.println("[OtpService] OTP mismatch for " + normalizedEmail);
            return false;
        }

        // Mark as used=true AND verified=true (step-verified, pending final action)
        token.setUsed(true);
        token.setVerified(true);
        otpTokenRepository.save(token);
        System.out.println("[OtpService] OTP step-verified for " + normalizedEmail);
        return true;
    }

    /**
     * Check if a step-verified OTP exists (used=true, verified=true). Consume it (set verified=false).
     */
    @Transactional
    public boolean consumeVerifiedOtp(String email, OtpToken.OtpPurpose purpose) {
        String normalizedEmail = email.toLowerCase().trim();
        Optional<OtpToken> tokenOpt = otpTokenRepository
                .findTopByEmailAndPurposeAndUsedTrueAndVerifiedTrueOrderByCreatedAtDesc(normalizedEmail, purpose);

        if (tokenOpt.isEmpty()) {
            System.out.println("[OtpService] No verified OTP found for " + normalizedEmail);
            return false;
        }

        OtpToken token = tokenOpt.get();
        // Grace period: allow 30 minutes from original expiry to complete the final step
        LocalDateTime gracePeriod = token.getExpiresAt().plusMinutes(30);
        if (LocalDateTime.now().isAfter(gracePeriod)) {
            System.out.println("[OtpService] Verified OTP grace period expired for " + normalizedEmail);
            return false;
        }

        token.setVerified(false); // consume it
        otpTokenRepository.save(token);
        System.out.println("[OtpService] Verified OTP consumed for " + normalizedEmail);
        return true;
    }

    @Scheduled(fixedRate = 3600000)
    @Transactional
    public void cleanExpiredOtps() {
        otpTokenRepository.deleteExpiredTokens(LocalDateTime.now());
    }
}

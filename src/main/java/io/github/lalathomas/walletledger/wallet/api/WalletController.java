package io.github.lalathomas.walletledger.wallet.api;

import io.github.lalathomas.walletledger.wallet.application.MoneyMovementResult;
import io.github.lalathomas.walletledger.wallet.application.ReservationResult;
import io.github.lalathomas.walletledger.wallet.application.ReservationService;
import io.github.lalathomas.walletledger.wallet.application.TransferResult;
import io.github.lalathomas.walletledger.wallet.application.TransferService;
import io.github.lalathomas.walletledger.wallet.application.WalletReconciliationService;
import io.github.lalathomas.walletledger.wallet.application.WalletService;
import io.github.lalathomas.walletledger.wallet.application.WalletSnapshot;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/wallets")
public class WalletController {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    static final String IDEMPOTENT_REPLAYED_HEADER = "Idempotent-Replayed";

    private final WalletService walletService;
    private final TransferService transferService;
    private final ReservationService reservationService;
    private final WalletReconciliationService reconciliationService;

    public WalletController(
            WalletService walletService,
            TransferService transferService,
            ReservationService reservationService,
            WalletReconciliationService reconciliationService
    ) {
        this.walletService = walletService;
        this.transferService = transferService;
        this.reservationService = reservationService;
        this.reconciliationService = reconciliationService;
    }

    @PostMapping
    public ResponseEntity<WalletResponse> createWallet(
            @Valid @RequestBody CreateWalletRequest request
    ) {
        WalletSnapshot wallet = walletService.createWallet(request.playerId());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{playerId}/balance")
                .buildAndExpand(wallet.playerId()).toUri();
        return ResponseEntity.created(location).body(WalletResponse.from(wallet));
    }

    @PostMapping("/{playerId}/credits")
    public ResponseEntity<MoneyMovementResponse> credit(
            @PathVariable UUID playerId,
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String key,
            @Valid @RequestBody MoneyMovementRequest request
    ) {
        return movementResponse(walletService.credit(playerId, request.toCommand(key)));
    }

    @PostMapping("/{playerId}/debits")
    public ResponseEntity<MoneyMovementResponse> debit(
            @PathVariable UUID playerId,
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String key,
            @Valid @RequestBody MoneyMovementRequest request
    ) {
        return movementResponse(walletService.debit(playerId, request.toCommand(key)));
    }

    @PostMapping("/{playerId}/transactions/{transactionId}/refund")
    public ResponseEntity<MoneyMovementResponse> refund(
            @PathVariable UUID playerId,
            @PathVariable UUID transactionId,
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String key,
            @Valid @RequestBody RefundRequest request
    ) {
        return movementResponse(walletService.refund(
                playerId, transactionId, request.toCommand(key)
        ));
    }

    @PostMapping("/{playerId}/transfers")
    public ResponseEntity<TransferResponse> transfer(
            @PathVariable UUID playerId,
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String key,
            @Valid @RequestBody TransferRequest request
    ) {
        TransferResult result = transferService.transfer(playerId, request.toCommand(key));
        return replayResponse(TransferResponse.from(result), result.replayed());
    }

    @PostMapping("/{playerId}/reservations")
    public ResponseEntity<ReservationResponse> reserve(
            @PathVariable UUID playerId,
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String key,
            @Valid @RequestBody CreateReservationRequest request
    ) {
        ReservationResult result = reservationService.reserve(playerId, request.toCommand(key));
        return replayResponse(ReservationResponse.from(result), result.replayed());
    }

    @PostMapping("/{playerId}/reservations/{reservationId}/capture")
    public ResponseEntity<ReservationResponse> capture(
            @PathVariable UUID playerId,
            @PathVariable UUID reservationId,
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String key,
            @Valid @RequestBody ReservationActionRequest request
    ) {
        ReservationResult result = reservationService.capture(
                playerId, reservationId, request.toCommand(key)
        );
        return replayResponse(ReservationResponse.from(result), result.replayed());
    }

    @PostMapping("/{playerId}/reservations/{reservationId}/release")
    public ResponseEntity<ReservationResponse> release(
            @PathVariable UUID playerId,
            @PathVariable UUID reservationId,
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String key,
            @Valid @RequestBody ReservationActionRequest request
    ) {
        ReservationResult result = reservationService.release(
                playerId, reservationId, request.toCommand(key)
        );
        return replayResponse(ReservationResponse.from(result), result.replayed());
    }

    @GetMapping("/{playerId}/balance")
    public BalanceResponse getBalance(@PathVariable UUID playerId) {
        return BalanceResponse.from(walletService.getBalance(playerId));
    }

    @GetMapping("/{playerId}/reconciliation")
    public ReconciliationResponse reconcile(@PathVariable UUID playerId) {
        return ReconciliationResponse.from(reconciliationService.reconcile(playerId));
    }

    @GetMapping("/{playerId}/transactions")
    public TransactionHistoryResponse getHistory(
            @PathVariable UUID playerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return TransactionHistoryResponse.from(walletService.getHistory(playerId, page, size));
    }

    private static ResponseEntity<MoneyMovementResponse> movementResponse(
            MoneyMovementResult result
    ) {
        return replayResponse(MoneyMovementResponse.from(result), result.replayed());
    }

    private static <T> ResponseEntity<T> replayResponse(T body, boolean replayed) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(IDEMPOTENT_REPLAYED_HEADER, Boolean.toString(replayed));
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }
}

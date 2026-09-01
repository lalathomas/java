package io.github.lalathomas.walletledger.wallet.api;

import io.github.lalathomas.walletledger.wallet.application.MoneyMovementResult;
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

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @PostMapping
    public ResponseEntity<WalletResponse> createWallet(
            @Valid @RequestBody CreateWalletRequest request
    ) {
        WalletSnapshot wallet = walletService.createWallet(request.playerId());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{playerId}/balance")
                .buildAndExpand(wallet.playerId())
                .toUri();
        return ResponseEntity.created(location).body(WalletResponse.from(wallet));
    }

    @PostMapping("/{playerId}/credits")
    public ResponseEntity<MoneyMovementResponse> credit(
            @PathVariable UUID playerId,
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody MoneyMovementRequest request
    ) {
        MoneyMovementResult result = walletService.credit(
                playerId,
                request.toCommand(idempotencyKey)
        );
        return movementResponse(result);
    }

    @PostMapping("/{playerId}/debits")
    public ResponseEntity<MoneyMovementResponse> debit(
            @PathVariable UUID playerId,
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody MoneyMovementRequest request
    ) {
        MoneyMovementResult result = walletService.debit(
                playerId,
                request.toCommand(idempotencyKey)
        );
        return movementResponse(result);
    }

    @PostMapping("/{playerId}/transactions/{transactionId}/refund")
    public ResponseEntity<MoneyMovementResponse> refund(
            @PathVariable UUID playerId,
            @PathVariable UUID transactionId,
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody RefundRequest request
    ) {
        MoneyMovementResult result = walletService.refund(
                playerId,
                transactionId,
                request.toCommand(idempotencyKey)
        );
        return movementResponse(result);
    }

    @GetMapping("/{playerId}/balance")
    public BalanceResponse getBalance(@PathVariable UUID playerId) {
        return BalanceResponse.from(walletService.getBalance(playerId));
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
        HttpHeaders headers = new HttpHeaders();
        headers.set(IDEMPOTENT_REPLAYED_HEADER, Boolean.toString(result.replayed()));
        return new ResponseEntity<>(MoneyMovementResponse.from(result), headers, HttpStatus.OK);
    }
}

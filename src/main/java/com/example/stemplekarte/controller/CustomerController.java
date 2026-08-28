package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Customer;
import com.example.stemplekarte.model.CustomerCard;
import com.example.stemplekarte.service.CardService;
import com.example.stemplekarte.service.CustomerService;
import com.example.stemplekarte.wallet.ApplePassService;
import com.example.stemplekarte.wallet.GoogleWalletService;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

@Tag(name = "Customer", description = "Kunden und ihre Karten")
@RestController
@RequestMapping("/api/customer")
public class CustomerController {

    private static final Logger log = LoggerFactory.getLogger(CustomerController.class);

    @Value("${stempelkarte.base-url:http://localhost:8080}")
    private String baseUrl;

    private final CustomerService customerService;
    private final CardService cardService;
    private final GoogleWalletService googleWalletService;
    private final ApplePassService applePassService;

    public CustomerController(CustomerService customerService,
                              CardService cardService,
                              GoogleWalletService googleWalletService,
                              ApplePassService applePassService) {
        this.customerService = customerService;
        this.cardService = cardService;
        this.googleWalletService = googleWalletService;
        this.applePassService = applePassService;
    }

    public record CreateCustomerRequest(@NotBlank String name, @Email @NotBlank String email) {}

    public record CustomerCardResponse(String customerCardId, String cardId, String cardName,
                                       String shopName, int stamps, int rewardThreshold,
                                       boolean rewardAvailable, int totalRewards) {
        static CustomerCardResponse from(CustomerCard cc) {
            return new CustomerCardResponse(
                    cc.getId(),
                    cc.getCard().getId(),
                    cc.getCard().getName(),
                    cc.getCard().getShop().getName(),
                    cc.getStamps(),
                    cc.getCard().getRewardThreshold(),
                    cc.getStamps() >= cc.getCard().getRewardThreshold(),
                    cc.getTotalRewards()
            );
        }
    }

    @Operation(summary = "Kunde anlegen oder bestehenden per E-Mail finden")
    @PostMapping
    public Map<String, String> getOrCreate(@Valid @RequestBody CreateCustomerRequest req) {
        Customer c = customerService.getOrCreate(req.name(), req.email());
        return Map.of("id", c.getId(), "name", c.getName(), "email", c.getEmail());
    }

    @Operation(summary = "Alle Karten eines Kunden abrufen")
    @GetMapping("/{customerId}/cards")
    public List<CustomerCardResponse> getCards(@PathVariable String customerId) {
        return customerService.getAllCardsOfCustomer(customerId)
                .stream().map(CustomerCardResponse::from).toList();
    }

    @Operation(summary = "Status einer bestimmten Karte des Kunden")
    @GetMapping("/{customerId}/card/{cardId}")
    public CustomerCardResponse getCard(@PathVariable String customerId,
                                        @PathVariable String cardId,
                                        HttpServletRequest request,
                                        HttpServletResponse response) {
        // no-store: das Kunden-Handy soll diesen Stand nie aus dem Browser-Cache
        // servieren - der Live-Abgleich der Karte haengt daran.
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");

        CustomerCard cc = customerService.getOrCreateCustomerCard(customerId, cardId);
        CustomerCardResponse dto = CustomerCardResponse.from(cc);

        // Mitschreiben, um seltene "Stempel kommt beim Kunden nicht an"-Faelle
        // nachvollziehen zu koennen: welcher Stand wurde geliefert, an welches Geraet.
        String gezeigt = request.getParameter("shown");   // vom Handy mitgeschickter, gerade angezeigter Stand
        String ua = request.getHeader("User-Agent");
        if (gezeigt != null && !gezeigt.equals(String.valueOf(dto.stamps()))) {
            log.info("Karten-Abgleich ABWEICHUNG: customerCard={} card={} handyZeigte={} serverHat={} ua=\"{}\"",
                    cc.getId(), cardId, gezeigt, dto.stamps(), ua);
        } else {
            log.info("Karten-Abgleich: customerCard={} card={} stamps={} ua=\"{}\"",
                    cc.getId(), cardId, dto.stamps(), ua);
        }
        return dto;
    }

    @Operation(summary = "QR-Code als PNG fuer Stempel-Scan")
    @GetMapping(value = "/{customerId}/card/{cardId}/qr", produces = MediaType.IMAGE_PNG_VALUE)
    public byte[] qr(@PathVariable String customerId,
                     @PathVariable String cardId) throws Exception {
        customerService.getOrCreateCustomerCard(customerId, cardId);
        String landingUrl = baseUrl + "/karte/" + customerId + "/" + cardId;
        var matrix = new QRCodeWriter().encode(landingUrl, BarcodeFormat.QR_CODE, 280, 280);
        var baos = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(matrix, "PNG", baos);
        return baos.toByteArray();
    }

    @Operation(summary = "QR-Code fuer Laden-Aushang (ohne Kunden-ID)")
    @GetMapping(value = "/card/{cardId}/qr-shop", produces = MediaType.IMAGE_PNG_VALUE)
    public byte[] shopQr(@PathVariable String cardId) throws Exception {
        cardService.getById(cardId);
        String landingUrl = baseUrl + "/karte-neu/" + cardId;
        var matrix = new QRCodeWriter().encode(landingUrl, BarcodeFormat.QR_CODE, 600, 600);
        var baos = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(matrix, "PNG", baos);
        return baos.toByteArray();
    }

    @Operation(summary = "Google Wallet Save-Link")
    @GetMapping("/{customerId}/card/{cardId}/google-pass")
    public Map<String, String> googlePass(@PathVariable String customerId,
                                          @PathVariable String cardId) {
        CustomerCard cc = customerService.getOrCreateCustomerCard(customerId, cardId);
        String url = googleWalletService.generateSaveUrl(cc);
        return Map.of("saveUrl", url);
    }

    @Operation(summary = "Apple Wallet Pass herunterladen (.pkpass)")
    @GetMapping(value = "/{customerId}/card/{cardId}/apple-pass",
            produces = "application/vnd.apple.pkpass")
    public ResponseEntity<byte[]> applePass(@PathVariable String customerId,
                                            @PathVariable String cardId) throws Exception {
        CustomerCard cc = customerService.getOrCreateCustomerCard(customerId, cardId);
        byte[] passBytes = applePassService.generatePass(cc);

        String filename = "stampit-" + cc.getCard().getShop().getName()
                .replaceAll("[^a-zA-Z0-9]", "_") + ".pkpass";

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.apple.pkpass"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .body(passBytes);
    }
}
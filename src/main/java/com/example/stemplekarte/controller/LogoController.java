package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.ShopRepository;
import com.example.stemplekarte.security.JwtAuthFilter;
import com.example.stemplekarte.wallet.CloudinaryService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Tag(name = "Logo", description = "Logo-Upload fuer Laden")
@RestController
@RequestMapping("/api/shop")
public class LogoController {

    private final ShopRepository shopRepo;
    private final CloudinaryService cloudinary;

    public LogoController(ShopRepository shopRepo, CloudinaryService cloudinary) {
        this.shopRepo = shopRepo;
        this.cloudinary = cloudinary;
    }

    // originalBase64 (optional): unbeschnittenes Bild, damit spaeter erneut
    // zugeschnitten werden kann.
    public record LogoUploadRequest(String base64, String extension, String originalBase64) {}

    @PostMapping("/logo")
    public Map<String, String> uploadLogo(@RequestBody LogoUploadRequest req,
                                          Authentication auth) {
        Shop shop = ((JwtAuthFilter.ShopPrincipal) auth.getPrincipal()).shop();

        String url = cloudinary.upload(req.base64(), "logo-" + shop.getId(),
                CloudinaryService.ImageType.LOGO);
        shop.update(null, url, null, null, null);

        Map<String, String> out = new HashMap<>();
        out.put("logoUrl", url);
        if (req.originalBase64() != null && !req.originalBase64().isBlank()) {
            String orig = cloudinary.upload(req.originalBase64(), "logo-orig-" + shop.getId(),
                    CloudinaryService.ImageType.ORIGINAL);
            shop.setLogoOriginalUrl(orig);
            out.put("logoOriginalUrl", orig);
        }
        shopRepo.save(shop);
        return out;
    }
}
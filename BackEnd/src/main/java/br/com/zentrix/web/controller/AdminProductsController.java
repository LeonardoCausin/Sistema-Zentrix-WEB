package br.com.zentrix.web.controller;

import br.com.zentrix.web.dto.ProductPriceRequest;
import br.com.zentrix.web.dto.ProductRequest;
import br.com.zentrix.web.dto.ProductStatusRequest;
import br.com.zentrix.web.service.AuthContext;
import br.com.zentrix.web.service.BusinessOperationsService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/admin/produtos", "/api/admin/products"})
public class AdminProductsController {
    private final BusinessOperationsService operationsService;

    public AdminProductsController(BusinessOperationsService operationsService) {
        this.operationsService = operationsService;
    }

    @GetMapping
    public List<Map<String, Object>> list(
            @RequestParam(defaultValue = "all") String store,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        return operationsService.adminProducts(AuthContext.tenantId(), store, search, category, status, limit, offset);
    }

    @GetMapping("/{code}")
    public Map<String, Object> detail(
            @PathVariable String code,
            @RequestParam(defaultValue = "WEB") String store
    ) {
        return operationsService.adminProduct(AuthContext.tenantId(), store, code);
    }

    @PostMapping
    public Map<String, Object> create(
            @RequestParam(defaultValue = "WEB") String store,
            @Valid @RequestBody ProductRequest request
    ) {
        return operationsService.createProduct(AuthContext.tenantId(), store, request);
    }

    @PutMapping("/{code}")
    public Map<String, Object> update(
            @PathVariable String code,
            @RequestParam(defaultValue = "WEB") String store,
            @Valid @RequestBody ProductRequest request
    ) {
        return operationsService.updateProduct(AuthContext.tenantId(), store, code, request);
    }

    @PatchMapping("/{code}/status")
    public Map<String, Object> status(
            @PathVariable String code,
            @RequestParam(defaultValue = "WEB") String store,
            @Valid @RequestBody ProductStatusRequest request
    ) {
        return operationsService.updateProductStatus(AuthContext.tenantId(), store, code, request.active(), request.reason());
    }

    @PatchMapping("/{code}/preco")
    public Map<String, Object> price(
            @PathVariable String code,
            @RequestParam(defaultValue = "WEB") String store,
            @Valid @RequestBody ProductPriceRequest request
    ) {
        return operationsService.updateProductPrice(AuthContext.tenantId(), store, code, request);
    }
}

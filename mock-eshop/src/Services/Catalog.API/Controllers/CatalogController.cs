// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License. See LICENSE in the project root.
// ---------------------------------------------------------------
//
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Logging;
using Microsoft.eShop.Services.Catalog.API.Services;

namespace Microsoft.eShop.Services.Catalog.API.Controllers;

/// <summary>
/// Catalog API entry point for the e-commerce checkout flow.
///
/// Receives the order from the front-end and delegates the discount
/// computation to <see cref="IDiscountService"/>. The reported v2.14.3
/// regression manifests in the <see cref="Checkout"/> action below
/// when the user supplies an unknown promotional code.
/// </summary>
[Route("api/v1/[controller]")]
[ApiController]
public class CatalogController : ControllerBase
{
    private readonly IDiscountService _discountService;
    private readonly ILogger<CatalogController> _logger;

    public CatalogController(
        IDiscountService discountService,
        ILogger<CatalogController> logger)
    {
        _discountService = discountService;
        _logger = logger;
    }

    /// <summary>
    /// Continues the checkout for a given order, applying the
    /// promotional discount if one was supplied. Reaches the
    /// buggy code path when <paramref name="discountCode"/>
    /// matches no row in the Promotions table.
    /// </summary>
    [HttpPost]
    [Route("items/checkout")]
    public IActionResult Checkout(string discountCode, decimal price)
    {
        _logger.LogInformation(
            "Checkout requested with code={Code} price={Price}",
            discountCode, price);

        // Delegate the discount math. The bug reported by the user
        // surfaces inside DiscountService.ApplyDiscount on line 142.
        var finalPrice = _discountService.ApplyDiscount(discountCode, price);

        return Ok(new { finalPrice });
    }
}

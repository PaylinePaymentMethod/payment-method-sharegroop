package com.payline.payment.sharegroop.service;

import com.payline.payment.sharegroop.exception.PluginException;
import com.payline.payment.sharegroop.utils.i18n.I18nService;
import com.payline.payment.sharegroop.utils.properties.ConfigProperties;
import com.payline.pmapi.bean.paymentform.bean.PaymentFormLogo;
import com.payline.pmapi.bean.paymentform.request.PaymentFormLogoRequest;
import com.payline.pmapi.bean.paymentform.response.logo.PaymentFormLogoResponse;
import com.payline.pmapi.bean.paymentform.response.logo.impl.PaymentFormLogoResponseFile;
import com.payline.pmapi.logger.LogManager;
import com.payline.pmapi.service.PaymentFormConfigurationService;
import org.apache.logging.log4j.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

public abstract class LogoPaymentFormConfigurationService implements PaymentFormConfigurationService {

    private static final Logger LOGGER = LogManager.getLogger(LogoPaymentFormConfigurationService.class);
    protected I18nService i18n = I18nService.getInstance();
    private ConfigProperties config = ConfigProperties.getInstance();

    @Override
    public PaymentFormLogoResponse getPaymentFormLogo(PaymentFormLogoRequest paymentFormLogoRequest) {
        Locale locale = paymentFormLogoRequest.getLocale();
        int height;
        int width;
        try {
            height = Integer.valueOf(config.get("logo.height"));
            width = Integer.valueOf(config.get("logo.width"));
        }
        catch( NumberFormatException e ){
            throw new PluginException( "Plugin config error: logo height and width must be integers", e );
        }

        return PaymentFormLogoResponseFile.PaymentFormLogoResponseFileBuilder.aPaymentFormLogoResponseFile()
                .withHeight( height )
                .withWidth( width )
                .withTitle(i18n.getMessage("paymentMethod.name", locale))
                .withAlt(i18n.getMessage("paymentMethod.name", locale) + " logo")
                .build();
    }

    @Override
    public PaymentFormLogo getLogo(String paymentMethodIdentifier, Locale locale) {
        final String filename = config.get("logo.filename");
        final String format = config.get("logo.format");
        final String contentType = config.get("logo.contentType");
        return getLogoByFilename(filename, format, contentType);
    }

    protected PaymentFormLogo getLogoByFilename(final String filename, final String format, final String contentType) {
        try (InputStream input = this.getClass().getClassLoader().getResourceAsStream(filename)) {
            if (input == null) {
                LOGGER.error("Unable to load file {}", filename);
                throw new PluginException("Plugin error: unable to load the logo file");
            }
            // Read logo file
            final BufferedImage logo = ImageIO.read(input);

            // Recover byte array from image
            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(logo, format, baos);

            return PaymentFormLogo.PaymentFormLogoBuilder.aPaymentFormLogo()
                    .withFile(baos.toByteArray())
                    .withContentType(contentType)
                    .build();
        } catch (final IOException e) {
            throw new PluginException("Plugin error: unable to read the logo", e);
        }
    }
}
/**
 * This file is part of alf.io.
 *
 * alf.io is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * alf.io is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with alf.io.  If not, see <http://www.gnu.org/licenses/>.
 */
/**
 * This file is part of alf.io.
 * <p>
 * alf.io is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * alf.io is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with alf.io.  If not, see <http://www.gnu.org/licenses/>.
 */
package alfio.controller.api.admin;

import alfio.controller.support.TemplateProcessor;
import alfio.manager.ExtensionManager;
import alfio.manager.FileUploadManager;
import alfio.manager.UploadedResourceManager;
import alfio.manager.user.UserManager;
import alfio.model.ContentLanguage;
import alfio.model.Event;
import alfio.model.PriceContainer;
import alfio.model.UploadedResource;
import alfio.model.modification.UploadBase64FileModification;
import alfio.model.user.Organization;
import alfio.repository.EventRepository;
import alfio.repository.user.OrganizationRepository;
import alfio.util.LocaleUtil;
import alfio.util.TemplateManager;
import alfio.util.TemplateResource;
import com.samskivert.mustache.MustacheException;
import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.Validate;
import org.springframework.context.MessageSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.springframework.web.bind.annotation.RequestMethod.POST;

@RestController
@RequestMapping("/admin/api")
@Log4j2
@AllArgsConstructor
public class ResourceController {


    private final UploadedResourceManager uploadedResourceManager;
    private final UserManager userManager;
    private final EventRepository eventRepository;
    private final MessageSource messageSource;
    private final TemplateManager templateManager;
    private final OrganizationRepository organizationRepository;
    private final FileUploadManager fileUploadManager;
    private final ExtensionManager extensionManager;


    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String handleSyntaxError(Exception ex) {
        log.warn("Exception in resource controller", ex);
        Optional<String> cause = Optional.ofNullable(ex.getCause())
            .filter(e -> e instanceof MustacheException || e instanceof TemplateProcessor.TemplateAccessException)
            .map(Throwable::getMessage);
        return cause.orElse("Something went wrong. Please check the syntax and retry");
    }

    @RequestMapping(value = "/overridable-template/", method = RequestMethod.GET)
    public List<TemplateResource> getOverridableTemplates() {
        return Stream.of(TemplateResource.values()).filter(TemplateResource::overridable).collect(Collectors.toList());
    }

    @RequestMapping(value = "/overridable-template/{name}/{locale}", method = RequestMethod.GET)
    public void getTemplate(@PathVariable("name") TemplateResource name, @PathVariable("locale") String locale, HttpServletResponse response) throws IOException {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        try (InputStream is = new ClassPathResource(name.classPath()).getInputStream()) {
            is.transferTo(os);
        }
        Locale loc = LocaleUtil.forLanguageTag(locale);
        String template = new String(os.toByteArray(), StandardCharsets.UTF_8);
        response.setContentType(MediaType.TEXT_PLAIN_VALUE);
        response.getWriter().print(TemplateManager.translate(template, loc, messageSource));
    }

    @RequestMapping(value = "/overridable-template/{name}/{locale}/preview", method = RequestMethod.POST)
    public void previewTemplate(@PathVariable("name") TemplateResource name, @PathVariable("locale") String locale,
                                @RequestParam(required = false, value = "organizationId") Integer organizationId,
                                @RequestParam(required = false, value = "eventId") Integer eventId,
                                @RequestBody UploadBase64FileModification template,
                                Principal principal,
                                HttpServletResponse response) throws IOException {


        Locale loc = LocaleUtil.forLanguageTag(locale);

        if (organizationId != null) {
            Event event;
            if (eventId!= null) {
                checkAccess(organizationId, eventId, principal);
                event =  eventRepository.findById(eventId);
            } else {
                checkAccess(organizationId, principal);
                event = new Event(-1, Event.EventType.INTERNAL, "TEST", "TEST", "TEST", "0", "0", ZonedDateTime.now(),
                    ZonedDateTime.now(), "Europe/Zurich", "http://localhost", "http://localhost", null,
                    "http://localhost", null, null, "CHF", BigDecimal.TEN, null, "42", organizationId,
                    ContentLanguage.ALL_LANGUAGES_IDENTIFIER, 0, PriceContainer.VatStatus.NONE, "1", Event.Status.PUBLIC);
            }

            Organization organization = organizationRepository.getById(organizationId);
            Optional<TemplateResource.ImageData> image = TemplateProcessor.extractImageModel(event, fileUploadManager);
            Map<String, Object> model = name.prepareSampleModel(organization, event, image);
            String renderedTemplate = templateManager.renderString(event, template.getFileAsString(), model, loc, name.getTemplateOutput());
            if(MediaType.TEXT_PLAIN_VALUE.equals(name.getRenderedContentType())) {
                response.addHeader("Content-Disposition", "attachment; filename="+name.name()+".txt");
                response.setContentType(MediaType.TEXT_PLAIN_VALUE);
                response.setCharacterEncoding("UTF-8");
                try(OutputStream os = response.getOutputStream()) {
                    StreamUtils.copy(renderedTemplate,StandardCharsets.UTF_8, os);
                }
            } else if (MediaType.APPLICATION_PDF_VALUE.equals(name.getRenderedContentType())) {
                try (OutputStream os = response.getOutputStream()) {
                    response.setContentType(MediaType.APPLICATION_PDF_VALUE);
                    response.addHeader("Content-Disposition", "attachment; filename="+name.name()+".pdf");
                    TemplateProcessor.renderToPdf(renderedTemplate, os, extensionManager, event);
                }
            } else {
                throw new IllegalStateException("cannot enter here!");
            }
        }
    }


    //------------------

    @RequestMapping(value = "/resource/", method = RequestMethod.GET)
    public List<UploadedResource> findAll(Principal principal) {
        checkAccess(principal);
        return uploadedResourceManager.findAll();
    }

    @RequestMapping(value = "/resource-organization/{organizationId}", method = RequestMethod.GET)
    public List<UploadedResource> findAllForOrganization(@PathVariable("organizationId") int organizationId, Principal principal) {
        checkAccess(organizationId, principal);
        return uploadedResourceManager.findAll(organizationId);
    }

    @RequestMapping(value = "/resource-event/{organizationId}/{eventId}", method = RequestMethod.GET)
    public List<UploadedResource> findAllForEvent(@PathVariable("organizationId") int organizationId, @PathVariable("eventId") int eventId, Principal principal) {
        checkAccess(organizationId, eventId, principal);
        return uploadedResourceManager.findAll(organizationId, eventId);
    }


    //------------------

    @RequestMapping(value = "/resource/{name}/metadata", method = RequestMethod.GET)
    public ResponseEntity<UploadedResource> getMetadata(@PathVariable("name") String name, Principal principal) {
        checkAccess(principal);
        if (uploadedResourceManager.hasResource(name)) {
            return new ResponseEntity<>(uploadedResourceManager.get(name), HttpStatus.OK);
        } else {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
    }

    @RequestMapping(value = "/resource-organization/{organizationId}/{name}/metadata", method = RequestMethod.GET)
    public ResponseEntity<UploadedResource> getMetadata(@PathVariable("organizationId") int organizationId, @PathVariable("name") String name, Principal principal) {
        checkAccess(organizationId, principal);
        if (uploadedResourceManager.hasResource(organizationId, name)) {
            return new ResponseEntity<>(uploadedResourceManager.get(organizationId, name), HttpStatus.OK);
        } else {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
    }

    @RequestMapping(value = "/resource-event/{organizationId}/{eventId}/{name}/metadata", method = RequestMethod.GET)
    public ResponseEntity<UploadedResource> getMetadata(@PathVariable("organizationId") int organizationId, @PathVariable("eventId") int eventId, @PathVariable("name") String name, Principal principal) {
        checkAccess(organizationId, eventId, principal);
        if (uploadedResourceManager.hasResource(organizationId, eventId, name)) {
            return new ResponseEntity<>(uploadedResourceManager.get(organizationId, eventId, name), HttpStatus.OK);
        } else {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
    }

    //------------------

    @RequestMapping(value = "/resource/", method = POST)
    public void uploadFile(@RequestBody UploadBase64FileModification upload, Principal principal) {
        checkAccess(principal);
        uploadedResourceManager.saveResource(upload).orElseThrow(IllegalArgumentException::new);
    }

    @RequestMapping(value = "/resource-organization/{organizationId}/", method = POST)
    public void uploadFile(@PathVariable("organizationId") int organizationId, @RequestBody UploadBase64FileModification upload, Principal principal) {
        checkAccess(organizationId, principal);
        uploadedResourceManager.saveResource(organizationId, upload).orElseThrow(IllegalArgumentException::new);
    }

    @RequestMapping(value = "/resource-event/{organizationId}/{eventId}/", method = POST)
    public void uploadFile(@PathVariable("organizationId") int organizationId, @PathVariable("eventId") int eventId, @RequestBody UploadBase64FileModification upload, Principal principal) {
        checkAccess(organizationId, eventId, principal);
        uploadedResourceManager.saveResource(organizationId, eventId, upload).orElseThrow(IllegalArgumentException::new);
    }

    //------------------
    @RequestMapping(value = "/resource/{name:.*}", method = RequestMethod.GET)
    public void outputContent(@PathVariable("name") String name, Principal principal, HttpServletResponse response) throws IOException {
        checkAccess(principal);
        if (!uploadedResourceManager.hasResource(name)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        UploadedResource metadata = uploadedResourceManager.get(name);
        try (OutputStream os = response.getOutputStream()) {
            response.setContentType(metadata.getContentType());
            response.setContentLength(metadata.getContentSize());
            uploadedResourceManager.outputResource(name, os);
        }
    }

    @RequestMapping(value = "/resource-organization/{organizationId}/{name:.*}", method = RequestMethod.GET)
    public void outputContent(@PathVariable("organizationId") int organizationId, @PathVariable("name") String name, Principal principal, HttpServletResponse response) throws IOException {
        checkAccess(organizationId, principal);
        if (!uploadedResourceManager.hasResource(organizationId, name)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        UploadedResource metadata = uploadedResourceManager.get(organizationId, name);
        try (OutputStream os = response.getOutputStream()) {
            response.setContentType(metadata.getContentType());
            response.setContentLength(metadata.getContentSize());
            uploadedResourceManager.outputResource(organizationId, name, os);
        }
    }

    @RequestMapping(value = "/resource-event/{organizationId}/{eventId}/{name:.*}", method = RequestMethod.GET)
    public void outputContent(@PathVariable("organizationId") int organizationId, @PathVariable("eventId") int eventId, @PathVariable("name") String name, Principal principal, HttpServletResponse response) throws IOException {
        checkAccess(organizationId, eventId, principal);
        if (!uploadedResourceManager.hasResource(organizationId, eventId, name)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        UploadedResource metadata = uploadedResourceManager.get(organizationId, eventId, name);
        try (OutputStream os = response.getOutputStream()) {
            response.setContentType(metadata.getContentType());
            response.setContentLength(metadata.getContentSize());
            uploadedResourceManager.outputResource(organizationId, eventId, name, os);
        }
    }

    //------------------

    @RequestMapping(value = "/resource/{name:.*}", method = RequestMethod.DELETE)
    public void delete(@PathVariable("name") String name, Principal principal) {
        checkAccess(principal);
        uploadedResourceManager.deleteResource(name);
    }

    @RequestMapping(value = "/resource-organization/{organizationId}/{name:.*}", method = RequestMethod.DELETE)
    public void delete(@PathVariable("organizationId") int organizationId, @PathVariable("name") String name, Principal principal) {
        checkAccess(organizationId, principal);
        uploadedResourceManager.deleteResource(organizationId, name);
    }

    @RequestMapping(value = "/resource-event/{organizationId}/{eventId}/{name:.*}", method = RequestMethod.DELETE)
    public void delete(@PathVariable("organizationId") int organizationId, @PathVariable("eventId") int eventId, @PathVariable("name") String name, Principal principal) {
        checkAccess(organizationId, eventId, principal);
        uploadedResourceManager.deleteResource(organizationId, eventId, name);
    }

    //------------------

    private void checkAccess(Principal principal) {
        Validate.isTrue(userManager.isAdmin(userManager.findUserByUsername(principal.getName())));
    }

    private void checkAccess(int organizationId, Principal principal) {
        Validate.isTrue(userManager.isOwnerOfOrganization(userManager.findUserByUsername(principal.getName()), organizationId));
    }

    private void checkAccess(int organizationId, int eventId, Principal principal) {
        Validate.isTrue(eventRepository.findEventAndOrganizationIdById(eventId).getOrganizationId() == organizationId && userManager.isOwnerOfOrganization(userManager.findUserByUsername(principal.getName()), organizationId));
    }
}

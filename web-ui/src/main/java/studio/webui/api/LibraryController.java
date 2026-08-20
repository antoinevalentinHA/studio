/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.api;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.Router;
import studio.core.v1.Constants;
import studio.webui.service.LibraryService;
import studio.webui.service.ProvenanceVerdict;

import java.nio.file.Path;
import java.util.Timer;
import java.util.TimerTask;

public class LibraryController {

    private static final Logger LOGGER = LoggerFactory.getLogger(LibraryController.class);
    
    public static Router apiRouter(Vertx vertx, LibraryService libraryService) {
        Router router = Router.router(vertx);

        // Local library device metadata
        router.get("/infos").blockingHandler(ctx -> {
            ctx.response()
                    .putHeader("content-type", "application/json")
                    .end(Json.encode(libraryService.libraryInfos()));
        });

        // Local library packs list
        router.get("/packs").blockingHandler(ctx -> {
            JsonArray libraryPacks = libraryService.packs();
            ctx.response()
                    .putHeader("content-type", "application/json")
                    .end(Json.encode(libraryPacks));
        });

        // Local library pack download
        router.post("/download").blockingHandler(ctx -> {
            String uuid = ctx.getBodyAsJson().getString("uuid");
            String packPath = ctx.getBodyAsJson().getString("path");
            libraryService.getRawPackFile(packPath)
                    .ifPresentOrElse(
                            file -> ctx.response()
                                    .putHeader("Content-Length", "" + file.length())
                                    .sendFile(file.getAbsolutePath()),
                            () -> {
                                LOGGER.error("Failed to download pack from library");
                                ctx.fail(500);
                            }
                    );
        });

        // Local library pack upload
        router.post("/upload").blockingHandler(ctx -> {
            String uuid = ctx.request().getFormAttribute("uuid");
            String packPath = ctx.request().getFormAttribute("path");
            boolean added = libraryService.addPackFile(packPath, ctx.fileUploads().iterator().next().uploadedFileName());
            if (added) {
                ctx.response()
                        .putHeader("content-type", "application/json")
                        .end(Json.encode(new JsonObject().put("success", true)));
            } else {
                LOGGER.error("Pack was not added to library");
                ctx.fail(500);
            }
        });

        // Local library pack conversion
        router.post("/convert").blockingHandler(ctx -> {
            String uuid = ctx.getBodyAsJson().getString("uuid");
            String packPath = ctx.getBodyAsJson().getString("path");
            Boolean allowEnriched = ctx.getBodyAsJson().getBoolean("allowEnriched", false);
            String format = ctx.getBodyAsJson().getString("format");
            // Perform conversion/uncompression asynchronously
            Future<Path> futureConvertedPack = Future.future();
            if (Constants.PACK_FORMAT_RAW.equalsIgnoreCase(format)) {
                Timer timer = new Timer();
                timer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        libraryService.addConvertedRawPackFile(packPath, allowEnriched)
                                .ifPresentOrElse(
                                        packPath -> futureConvertedPack.tryComplete(packPath),
                                        () -> futureConvertedPack.tryFail("Failed to read or convert pack to raw format"));
                        futureConvertedPack.tryComplete();
                    }
                }, 1000);
            } else if (Constants.PACK_FORMAT_FS.equalsIgnoreCase(format)) {
                Timer timer = new Timer();
                timer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        libraryService.addConvertedFsPackFile(packPath, allowEnriched)
                                .ifPresentOrElse(
                                        packPath -> futureConvertedPack.tryComplete(packPath),
                                        () -> futureConvertedPack.tryFail("Failed to read or convert pack to folder format"));
                        futureConvertedPack.tryComplete();
                    }
                }, 1000);
            } else if (Constants.PACK_FORMAT_ARCHIVE.equalsIgnoreCase(format)) {
                Timer timer = new Timer();
                timer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        libraryService.addConvertedArchivePackFile(packPath)
                                .ifPresentOrElse(
                                        packPath -> futureConvertedPack.tryComplete(packPath),
                                        () -> futureConvertedPack.tryFail("Failed to read or convert pack to folder format"));
                        futureConvertedPack.tryComplete();
                    }
                }, 1000);
            } else {
                ctx.fail(400);
                return;
            }
            futureConvertedPack.onComplete(maybeConvertedPack -> {
                if (maybeConvertedPack.succeeded()) {
                    // Return path to converted file within library
                    ctx.response()
                            .putHeader("content-type", "application/json")
                            .end(Json.encode(new JsonObject()
                                    .put("success", true)
                                    .put("path", maybeConvertedPack.result().toString())
                            ));
                } else {
                    LOGGER.error("Failed to read or convert pack");
                    ctx.fail(500, maybeConvertedPack.cause());
                }
            });
        });

        // Whether a converted pack still corresponds to the source it was made from. Only ever
        // asked when a pack is dropped on a device, never while listing: it reads both artefacts.
        // The answer is a verdict and nothing else — no digest, no record, no path — because the
        // client has no use for them and no business deciding this for itself.
        router.post("/verify-conversion").blockingHandler(ctx -> {
            String sourcePath = ctx.getBodyAsJson().getString("sourcePath");
            String convertedPath = ctx.getBodyAsJson().getString("convertedPath");
            try {
                ProvenanceVerdict verdict = libraryService.verifyConversion(sourcePath, convertedPath);
                ctx.response()
                        .putHeader("content-type", "application/json")
                        .end(Json.encode(new JsonObject().put("verdict", verdict.name())));
            } catch (IllegalArgumentException e) {
                // Naming something outside the library is a malformed request, not an uncertainty.
                // Answering UNKNOWN here would quietly accept a question that should be refused.
                LOGGER.error("Refused a conversion verification request", e);
                ctx.fail(400, e);
            }
        });

        // Remove pack from device
        router.post("/remove").blockingHandler(ctx -> {
            String packPath = ctx.getBodyAsJson().getString("path");
            boolean removed = libraryService.deletePack(packPath);
            if (removed) {
                ctx.response()
                        .putHeader("content-type", "application/json")
                        .end(Json.encode(new JsonObject().put("success", true)));
            } else {
                LOGGER.error("Pack was not removed from library");
                ctx.fail(500);
            }
        });

        return router;
    }
}

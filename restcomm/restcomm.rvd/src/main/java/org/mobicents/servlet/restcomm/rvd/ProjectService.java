package org.mobicents.servlet.restcomm.rvd;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;

import org.apache.commons.io.FileUtils;
import org.mobicents.servlet.restcomm.rvd.exceptions.InvalidServiceParameters;
import org.mobicents.servlet.restcomm.rvd.exceptions.ProjectDoesNotExist;
import org.mobicents.servlet.restcomm.rvd.exceptions.RvdException;
import org.mobicents.servlet.restcomm.rvd.exceptions.project.ProjectException;
import org.mobicents.servlet.restcomm.rvd.exceptions.project.UnsupportedProjectVersion;
import org.mobicents.servlet.restcomm.rvd.jsonvalidation.ProjectValidator;
import org.mobicents.servlet.restcomm.rvd.jsonvalidation.ValidationResult;
import org.mobicents.servlet.restcomm.rvd.jsonvalidation.exceptions.ValidationException;
import org.mobicents.servlet.restcomm.rvd.jsonvalidation.exceptions.ValidationFrameworkException;
import org.mobicents.servlet.restcomm.rvd.model.client.ProjectItem;
import org.mobicents.servlet.restcomm.rvd.model.client.ProjectState;
import org.mobicents.servlet.restcomm.rvd.model.client.StateHeader;
import org.mobicents.servlet.restcomm.rvd.model.client.WavItem;
import org.mobicents.servlet.restcomm.rvd.model.project.RvdProject;
import org.mobicents.servlet.restcomm.rvd.storage.FsProjectStorage;
import org.mobicents.servlet.restcomm.rvd.storage.WorkspaceStorage;
import org.mobicents.servlet.restcomm.rvd.storage.exceptions.BadProjectHeader;
import org.mobicents.servlet.restcomm.rvd.storage.exceptions.StorageException;
import org.mobicents.servlet.restcomm.rvd.storage.exceptions.WavItemDoesNotExist;
import org.mobicents.servlet.restcomm.rvd.upgrade.UpgradeService;
import org.mobicents.servlet.restcomm.rvd.upgrade.UpgradeService.UpgradabilityStatus;
import org.mobicents.servlet.restcomm.rvd.utils.RvdUtils;
import org.mobicents.servlet.restcomm.rvd.utils.Unzipper;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;

import com.github.fge.jsonschema.core.exceptions.ProcessingException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class ProjectService {

    static final Logger logger = Logger.getLogger(ProjectService.class.getName());

    public enum Status {
        OK, UNKNOWN_VERSION, BAD, TOO_OLD, SHOULD_UPGRADE
    }

    private ServletContext servletContext; // TODO we have to find way other that directly through constructor parameter.

    RvdConfiguration settings;
    RvdContext rvdContext;
    WorkspaceStorage workspaceStorage;

    public ProjectService(RvdContext rvdContext, WorkspaceStorage workspaceStorage) {
        this.rvdContext = rvdContext;
        this.servletContext = rvdContext.getServletContext();
        //this.projectStorage = rvdContext.getProjectStorage();
        this.settings = rvdContext.getSettings();
        this.workspaceStorage = workspaceStorage;
    }

    // Used for testing. TODO create a ProjectService interface, ProjectServiceBuilder and separate implementation
    public ProjectService() {
    }

    /**
     * Builds the startUrl for an application based on the application name and the incoming httpRequest. It is depending on the
     * initial REST request that called this function. Usually this httpRequest comes either from user's browser when he runs
     * Admin-UI or RVD. However, this url will be used in Restcomm too. Make sure that Restcomm can access the generated the
     * same way client's browser does.
     *
     * @param projectName
     * @param httpRequest
     * @return An absolute url pointing to the starting URL of the application
     * @throws UnsupportedEncodingException
     * @throws URISyntaxException
     */
    /*
    public static String getStartUrlForProject(String projectName, HttpServletRequest httpRequest) throws URISyntaxException {
        URI startURI = new URI(httpRequest.getScheme(), null, httpRequest.getServerName(),
                (httpRequest.getServerPort() == 80 ? -1 : httpRequest.getServerPort()), httpRequest.getContextPath()
                        + httpRequest.getServletPath() + "/apps/" + projectName + "/controller", null, null);

        //logger.info("startURI.getPath(): " + startURI.getPath());
        //logger.info("startURI.getRawPath(): " + startURI.getRawPath());
        //logger.info("startURI.toASCIIString(): " + startURI.toASCIIString());
        return startURI.getRawPath();  //toASCIIString();
    }*/

    /**
     * Builds the start url for a project
     * @param projectName
     * @return
     * @throws RvdException
     */
    public String buildStartUrl(String projectName) throws ProjectException {
        //servletContext.getS
        String path = servletContext.getContextPath() + "/" + RvdConfiguration.REST_SERVICES_PATH + "/apps/" + projectName + "/controller";
        URI uri;
        try {
            uri = new URI(null, null, path, null);
        } catch (URISyntaxException e) {
            throw new ProjectException("Error building startUrl for project " + projectName, e);
        }
        return uri.getRawPath();

    }

    /**
     * Populates an application list with startup urls for each application
     *
     * @param items
     * @param httpRequest
     * @throws URISyntaxException
     * @throws RvdException
     */
    public void fillStartUrlsForProjects(List<ProjectItem> items, HttpServletRequest httpRequest)
            throws ProjectException {
        for (ProjectItem item : items) {
            item.setStartUrl( buildStartUrl(item.getName()));
        }
    }

    /**
     * Returns the projects owned by ownerFilter (in addition to those that belong to none and are freely accessible). If ownerFilter is null only
     * freely accessible projectds are returned.
     * @param ownerFilter
     * @throws StorageException
     */
    public List<ProjectItem> getAvailableProjectsByOwner(String ownerFilter) throws StorageException {

        List<ProjectItem> items = new ArrayList<ProjectItem>();
        for (String entry : FsProjectStorage.listProjectNames(workspaceStorage) ) {

            String kind = "voice";
            String owner = null;
            ProjectItem item = new ProjectItem();
            item.setName(entry);
            try {
                StateHeader header = FsProjectStorage.loadStateHeader(entry, workspaceStorage);
                item.setStatus(ProjectService.projectStatus(header));
                kind = header.getProjectKind();
                owner = header.getOwner();
            } catch ( BadProjectHeader e ) {
                // for old projects
                JsonParser parser = new JsonParser();
                //JsonObject root_element = parser.parse(projectStorage.loadProjectState(entry)).getAsJsonObject();
                JsonObject root_element = parser.parse(FsProjectStorage.loadProjectString(entry, workspaceStorage)).getAsJsonObject();
                JsonElement projectKind_element = root_element.get("projectKind");
                if ( projectKind_element != null ) {
                    kind = projectKind_element.getAsString();
                }
                item.setStatus(Status.BAD);
            }

            if ( ownerFilter != null ) {
                if ( owner == null || owner.equals(ownerFilter) ) {
                    item.setKind(kind);
                    items.add(item);
                }
            } else {
                item.setKind(kind);
                items.add(item);
            }
        }
        return items;
    }

    static Status projectStatus(StateHeader header) {
        if (header == null || header.getVersion() == null)
            return Status.BAD;
        try {
            UpgradabilityStatus upgradable = UpgradeService.checkUpgradability(header.getVersion(), RvdConfiguration.getRvdProjectVersion());
            if (upgradable == UpgradabilityStatus.NOT_NEEDED)
                return Status.OK;
            else
            if (upgradable == UpgradabilityStatus.UPGRADABLE)
                return Status.SHOULD_UPGRADE;
            else
            if (upgradable == UpgradabilityStatus.NOT_SUPPORTED)
                return Status.UNKNOWN_VERSION;
            else
                return Status.BAD; // this should not happen

        } catch (Exception e) {
            return Status.UNKNOWN_VERSION;
        }
    }

    public ProjectState createProject(String projectName, String kind, String owner) throws StorageException, InvalidServiceParameters {
        if ( !"voice".equals(kind) && !"ussd".equals(kind) && !"sms".equals(kind) )
            throw new InvalidServiceParameters("Invalid project kind specified - '" + kind + "'");

        ProjectState state = null;
        if ( "voice".equals(kind) )
            state = ProjectState.createEmptyVoice(owner);
        else
        if ( "ussd".equals(kind) )
            state = ProjectState.createEmptyUssd(owner);
        else
        if ( "sms".equals(kind) )
            state = ProjectState.createEmptySms(owner);

        //projectStorage.createProjectSlot(projectName);
        FsProjectStorage.createProjectSlot(projectName, workspaceStorage);

        FsProjectStorage.storeProject(true, state, projectName, workspaceStorage);
        return state;
    }

    public ValidationResult validateProject(String stateData) throws RvdException {
        try {
            ProjectValidator validator = new ProjectValidator();
            ValidationResult result = validator.validate(stateData);
            return result;
        } catch (IOException e) {
            throw new RvdException("Internal error while validating raw project",e);
        } catch (ProcessingException e) {
            throw new ValidationFrameworkException("Error while validating raw project",e);
        }
    }

    public void updateProject(HttpServletRequest request, String projectName, ProjectState existingProject) throws RvdException {
        String stateData = null;
        try {
            stateData = IOUtils.toString(request.getInputStream(), Charset.forName("UTF-8"));
        } catch (IOException e) {
            throw new RvdException("Internal error while retrieving raw project",e);
        }

        ValidationResult validationResult = validateProject(stateData);
        // then save
        ProjectState state = rvdContext.getMarshaler().toModel(stateData, ProjectState.class);
        // Make sure the current RVD project version is set
        state.getHeader().setVersion(settings.getRvdProjectVersion());
        // preserve project owner
        state.getHeader().setOwner(existingProject.getHeader().getOwner());
        //projectStorage.storeProject(projectName, state, false);
        FsProjectStorage.storeProject(false, state, projectName, workspaceStorage);

        if ( !validationResult.isSuccess() ) {
            throw new ValidationException(validationResult);
        }
    }

    public void deleteProject(String projectName) throws ProjectDoesNotExist, StorageException {
        if (! FsProjectStorage.projectExists(projectName,workspaceStorage))
            throw new ProjectDoesNotExist();
        FsProjectStorage.deleteProject(projectName,workspaceStorage);
    }

    public InputStream archiveProject(String projectName) throws StorageException {
        return FsProjectStorage.archiveProject(projectName,workspaceStorage);
    }

    public void importProjectFromRawArchive(InputStream archiveStream, String applicationSid, String owner) throws RvdException {
        File archiveFile = new File(applicationSid);
        String projectName = FilenameUtils.getBaseName(archiveFile.getName());

        // First unzip to temp dir
        File tempProjectDir;
        try {
            tempProjectDir = RvdUtils.createTempDir();
        } catch (RvdException e) {
            throw new StorageException("Error importing project from archive. Cannot create temp directory for project: " + projectName, e );
        }
        Unzipper unzipper = new Unzipper(tempProjectDir);
        unzipper.unzip(archiveStream);

        importProject(tempProjectDir, applicationSid, owner );
    }

    public String importProject(File tempProjectDir, String suggestedName, String owner) throws RvdException {
        try {
            // check project version for compatibility
            String stateFilename = tempProjectDir.getPath() + "/state";
            FileReader reader = new FileReader(stateFilename);
            JsonParser parser = new JsonParser();
            JsonElement rootElement = parser.parse(reader);
            String version = rootElement.getAsJsonObject().get("header").getAsJsonObject().get("version").getAsString();
            // Create a temporary workspace storage.
            WorkspaceStorage tempStorage = new WorkspaceStorage(tempProjectDir.getParent(), rvdContext.getMarshaler());
            // is this project compatible (current RVD can open and run without upgrading) ?
            if ( ! UpgradeService.checkBackwardCompatible(version, RvdConfiguration.getRvdProjectVersion()) ) {
                if ( UpgradeService.checkUpgradability(version, RvdConfiguration.getRvdProjectVersion()) == UpgradeService.UpgradabilityStatus.UPGRADABLE ) {
                    UpgradeService upgradeService = new UpgradeService(tempStorage);
                    upgradeService.upgradeProject(tempProjectDir.getName());
                    BuildService buildService = new BuildService(tempStorage);
                    buildService.buildProject(tempProjectDir.getName());
                } else {
                    // project cannot be upgraded
                    throw new UnsupportedProjectVersion("Imported project version (" + version + ") not supported");
                }
            }
            // project is either compatible or was upgraded
            ProjectState state = FsProjectStorage.loadProject(tempProjectDir.getName(), tempStorage);
            state.getHeader().setOwner(owner);
            FsProjectStorage.storeProject(false, state, tempProjectDir.getName(), tempStorage);

            // TODO Make these an atomic action!
            suggestedName = FsProjectStorage.getAvailableProjectName(suggestedName, workspaceStorage);
            FsProjectStorage.createProjectSlot(suggestedName, workspaceStorage);

            FsProjectStorage.importProjectFromDirectory(tempProjectDir, suggestedName, true, workspaceStorage);
            return suggestedName;

        } catch ( UnsupportedProjectVersion e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException("Error importing project from archive.",e);
        } finally {
            FileUtils.deleteQuietly(tempProjectDir);
        }
    }

    public void addWavToProject(String projectName, String wavName, InputStream wavStream) throws StorageException {
        FsProjectStorage.storeWav(projectName, wavName, wavStream, workspaceStorage);
    }

    public List<WavItem> getWavs(String appName) throws StorageException {
        return FsProjectStorage.listWavs(appName, workspaceStorage);
    }

    public void removeWavFromProject(String projectName, String wavName) throws WavItemDoesNotExist {
        FsProjectStorage.deleteWav(projectName, wavName,workspaceStorage);
    }

    /**
     * Loads the project specified into an rvd project object
     * @param projectName
     * @return
     * @throws RvdException
     */

    public RvdProject load(String projectName) throws RvdException {
        String projectJson = FsProjectStorage.loadProjectString(projectName, workspaceStorage);
        RvdProject project = RvdProject.fromJson(projectName, projectJson);
        return project;
    }


}

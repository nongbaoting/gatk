package org.broadinstitute.hellbender.cmdline.GATKPlugin;

import org.broadinstitute.barclay.argparser.CommandLineException;
import org.broadinstitute.barclay.argparser.CommandLinePluginDescriptor;
import org.broadinstitute.hellbender.engine.filters.ReadFilter;
import org.broadinstitute.hellbender.tools.walkers.annotator.Annotation;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * A base class for descriptors for plugins that can be dynamically discovered by the
 * command line parser and specified as command line arguments. An instance of each
 * plugin descriptor to be used should be passed to the command line parser, and will
 * be queried to find the class and package names to search for all plugin classes
 * that should be discovered dynamically. The command line parser will find all such
 * classes, and delegate to the descriptor to obtain the corresponding plugin instance;
 * the object returned to the parser is then added to the parser's list of argument sources.
 *
 * Descriptors (sub)classes should have at least one @Argument used to accumulate the
 * user-specified instances of the plugin seen on the command line. Allowed values for
 * this argument are the simple class names of the discovered plugin subclasses.
 *
 * Plugin (sub)classes:
 *
 * - should subclass a common base class (the name of which is returned by the descriptor)
 * - may live in any one of the packages returned by the descriptor {@Link #getPackageNames},
 *   but must have a unique simple name to avoid command line name collisions.
 * - should contain @Arguments for any values they wish to collect. @Arguments may be
 *   optional or required. If required, the arguments are in effect "provisionally
 *   required" in that they are contingent on the specific plugin being specified on
 *   the command line; they will only be marked by the command line parser as missing
 *   if the they have not been specified on the command line, and the plugin class
 *   containing the plugin argument *has* been specified on the command line (as
 *   determined by the command line parser via a call to isDependentArgumentAllowed).
 *
 * NOTE: plugin class @Arguments that are marked "optional=false" should be not have a primitive
 * type, and should not have an initial value, as the command line parser will interpret these as
 * having been set even if they have not been specified on the command line. Conversely, @Arguments
 * that are optional=true should have an initial value, since they parser will not require them
 * to be set in the command line.
 *
 * The methods for each descriptor are called in the following order:
 *
 *  getPluginClass()/getPackageNames() - once when argument parsing begins (if the descriptor
 *  has been passed to the command line parser as a target descriptor)
 *
 *  getClassFilter() - once for each plugin subclass found
 *  getInstance() - once for each plugin subclass that isn't filtered out by getClassFilter
 *  validateDependentArgumentAllowed  - once for each plugin argument value that has been
 *  specified on the command line for a plugin that is controlled by this descriptor
 *
 *  validateArguments() - once when argument parsing is complete
 *  getAllInstances() - whenever the pluggable class consumer wants the resulting plugin instances
 *
 *  getAllowedValuesForDescriptorArgument is only called when the command line parser is constructing
 *  a help/usage message.
 */
public class GATKAnnotationPluginDescriptor  extends CommandLinePluginDescriptor<Annotation> {

    private static final String pluginPackageName = "org.broadinstitute.hellbender.tools.walkers.annotator";
    private static final Class<?> pluginBaseClass = org.broadinstitute.hellbender.tools.walkers.annotator.Annotation.class;

    /**
     * @param userArgs           Argument collection to control the exposure of the command line arguments.
     * @param toolDefaultFilters Default filters that may be supplied with arguments
     *                           on the command line. May be null.
     */
    public GATKAnnotationPluginDescriptor(final GATKReadFilterArgumentCollection userArgs, final List<Annotation> toolDefaultFilters) {
        this.userArgs = userArgs;
        if (null != toolDefaultFilters) {
            toolDefaultFilters.forEach(f -> {
                final Class<? extends ReadFilter> rfClass = f.getClass();
                // anonymous classes have a 0-length simple name, and thus cannot be accessed or
                // controlled by the user via the command line, but they should still be valid
                // as default filters, so use the full name to ensure that their map entries
                // don't clobber each other
                String className = rfClass.getSimpleName();
                if (className.length() == 0) {
                    className = rfClass.getName();
                }
                toolDefaultReadFilters.put(className, f);
            });
        }
    }

    /**
     * @param toolDefaultFilters Default filters that may be supplied with arguments
     *                           on the command line. May be null.
     */
    public GATKAnnotationPluginDescriptor(final List<Annotation> toolDefaultFilters) {
        this(new DefaultGATKReadFilterArgumentCollection(), toolDefaultFilters);
    }

    /**
     * @return the class object for the base class of all plugins managed by this descriptor
     */
    @Override
    public Class<?> getPluginClass() {return pluginBaseClass;}

    /**
     * A list of package names which will be searched for plugins managed by the descriptor.
     * @return
     */
    @Override
    public List<String> getPackageNames() {return Collections.singletonList(pluginPackageName);};

    /**
     * Return an instance of the specified pluggable class. The descriptor should
     * instantiate or otherwise obtain (possibly by having been provided an instance
     * through the descriptor's constructor) an instance of this plugin class.
     * The descriptor should maintain a list of these instances so they can later
     * be retrieved by {@link #getAllInstances}.
     *
     * In addition, implementations should recognize and reject any attempt to instantiate
     * a second instance of a plugin that has the same simple class name as another plugin
     * controlled by this descriptor (which can happen if they have different qualified names
     * within the base package used by the descriptor) since the user has no way to disambiguate
     * these on the command line).
     *
     * @param pluggableClass a plugin class discovered by the command line parser that
     *                       was not rejected by {@link #getClassFilter}
     * @return the instantiated object that will be used by the command line parser
     * as an argument source
     * @throws IllegalAccessException
     * @throws InstantiationException
     */
    //TODO
    @Override
    public Object getInstance(Class<?> pluggableClass) throws IllegalAccessException, InstantiationException {
        Annotation readFilter = null;
        final String simpleName = pluggableClass.getSimpleName();

        if (allDiscoveredReadFilters.containsKey(simpleName)) {
            // we found a plugin class with a name that collides with an existing class;
            // plugin names must be unique even across packages
            throw new IllegalArgumentException(
                    String.format("A plugin class name collision was detected (%s/%s). " +
                                    "Simple names of plugin classes must be unique across packages.",
                            pluggableClass.getName(),
                            allDiscoveredReadFilters.get(simpleName).getClass().getName())
            );
        } else if (toolDefaultReadFilters.containsKey(simpleName)) {
            // an instance of this class was provided by the tool as one of it's default filters;
            // use the default instance as the target for command line argument values
            // rather than creating a new one, in case it has state provided by the tool
            readFilter = toolDefaultReadFilters.get(simpleName);
        } else {
            readFilter = (ReadFilter) pluggableClass.newInstance();
        }

        // Add all filters to the allDiscoveredReadFilters list, even if the instance came from the
        // tool defaults list (we want the actual instances to be shared to preserve state)
        allDiscoveredReadFilters.put(simpleName, readFilter);
        return readFilter;
    }

    @Override
    public Set<String> getAllowedValuesForDescriptorArgument(String longArgName) {
        return null;
    }

    @Override
    public boolean isDependentArgumentAllowed(Class<?> dependentClass) {
        return false;
    }

    @Override
    public void validateArguments() throws CommandLineException {

    }

    @Override
    public List<Object> getDefaultInstances() {
        return null;
    }

    @Override
    public List<Annotation> getAllInstances() {
        return null;
    }

    @Override
    public Class<?> getClassForInstance(String pluginName) {
        return null;
    }
}
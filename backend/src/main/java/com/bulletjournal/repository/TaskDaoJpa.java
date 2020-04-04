package com.bulletjournal.repository;

import com.bulletjournal.authz.AuthorizationService;
import com.bulletjournal.authz.Operation;
import com.bulletjournal.contents.ContentType;
import com.bulletjournal.controller.models.CreateTaskParams;
import com.bulletjournal.controller.models.ProjectType;
import com.bulletjournal.controller.models.ReminderSetting;
import com.bulletjournal.controller.models.UpdateTaskParams;
import com.bulletjournal.controller.utils.ZonedDateTimeHelper;
import com.bulletjournal.exceptions.BadRequestException;
import com.bulletjournal.exceptions.ResourceNotFoundException;
import com.bulletjournal.hierarchy.HierarchyItem;
import com.bulletjournal.hierarchy.HierarchyProcessor;
import com.bulletjournal.hierarchy.TaskRelationsProcessor;
import com.bulletjournal.notifications.Event;
import com.bulletjournal.repository.models.*;
import com.bulletjournal.repository.utils.DaoHelper;
import com.bulletjournal.util.BuJoRecurrenceRule;
import com.google.gson.Gson;
import org.dmfs.rfc5545.DateTime;
import org.dmfs.rfc5545.recur.InvalidRecurrenceRuleException;
import org.dmfs.rfc5545.recur.RecurrenceRuleIterator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Repository
public class TaskDaoJpa extends ProjectItemDaoJpa<TaskContent> {

    private static final Gson GSON = new Gson();

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private ProjectDaoJpa projectDaoJpa;

    @Autowired
    private ProjectTasksRepository projectTasksRepository;

    @Autowired
    private AuthorizationService authorizationService;

    @Autowired
    private CompletedTaskRepository completedTaskRepository;

    @Autowired
    private UserDaoJpa userDaoJpa;

    @Autowired
    private TaskContentRepository taskContentRepository;

    @Autowired
    private SharedProjectItemDaoJpa sharedProjectItemDaoJpa;

    @Override
    public JpaRepository getJpaRepository() {
        return this.taskRepository;
    }

    /*
     * Get all tasks from project
     *
     * @param projectId
     * @param requester
     * @retVal List<com.bulletjournal.controller.models.Task> - a list of controller model tasks with labels
     */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public List<com.bulletjournal.controller.models.Task> getTasks(Long projectId, String requester) {
        Project project = this.projectDaoJpa.getProject(projectId, requester);
        if (project.isShared()) {
            return this.sharedProjectItemDaoJpa.getSharedProjectItems(requester, ProjectType.TODO);
        }

        Optional<ProjectTasks> projectTasksOptional = this.projectTasksRepository.findById(projectId);
        if (!projectTasksOptional.isPresent()) {
            return Collections.emptyList();
        }
        ProjectTasks projectTasks = projectTasksOptional.get();
        final Map<Long, Task> tasksMap = this.taskRepository.findTaskByProject(project)
                .stream().collect(Collectors.toMap(Task::getId, n -> n));
        return TaskRelationsProcessor.processRelations(tasksMap, projectTasks.getTasks())
                .stream()
                .map(task -> addLabels(task, tasksMap))
                .collect(Collectors.toList());
    }

    /*
     * Apply labels to tasks
     *
     * @param task
     * @param taskMap - Mapping relationship between TaskId and Task Instance
     * @retVal com.bulletjournal.controller.models.Task - Task instance with labels
     */
    private com.bulletjournal.controller.models.Task addLabels(
            com.bulletjournal.controller.models.Task task, Map<Long, Task> tasksMap) {
        List<com.bulletjournal.controller.models.Label> labels =
                getLabelsToProjectItem(tasksMap.get(task.getId()));
        task.setLabels(labels);
        for (com.bulletjournal.controller.models.Task subTask : task.getSubTasks()) {
            addLabels(subTask, tasksMap);
        }
        return task;
    }

    /*
     * Get completed task by task identifier
     *
     * 1. Get task from database
     * 2. Look up task labels and add to task
     *
     * @param requester
     * @param id
     * @retVal com.bulletjournal.controller.models.Task - controller model task with label
     */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public com.bulletjournal.controller.models.Task getTask(String requester, Long id) {
        Task task = this.getProjectItem(id, requester);
        List<com.bulletjournal.controller.models.Label> labels = this.getLabelsToProjectItem(task);
        return task.toPresentationModel(labels);
    }

    /*
     * Get completed tasks from database
     *
     * @param id
     * @retVal List<com.bulletjournal.controller.models.Task> - A list of completed tasks
     */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public com.bulletjournal.controller.models.Task getCompletedTask(Long id) {
        CompletedTask task = this.completedTaskRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Task " + id + " not found"));
        return task.toPresentationModel();
    }

    /*
     * Get assignee's reminding tasks and recurring reminding tasks from database.
     *
     * Reminding tasks qualifications:
     * 1. Reminding Time is before current time.
     * 2. Starting time is after the current time.
     *
     * @param assignee
     * @param now
     * @retVal List<com.bulletjournal.controller.models.Task> - A list of tasks to be reminded
     */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public List<com.bulletjournal.controller.models.Task> getRemindingTasks(String assignee, ZonedDateTime now) {
        Timestamp currentTime = Timestamp.from(now.toInstant());

        // Fetch regular reminding tasks
        List<com.bulletjournal.controller.models.Task> regularTasks = this.taskRepository
                .findRemindingTasks(assignee, currentTime)
                .stream()
                .map(TaskModel::toPresentationModel)
                .collect(Collectors.toList());

        // Fetch recurring reminding tasks
        List<com.bulletjournal.controller.models.Task> recurringTask = getRecurringTaskNeedReminding(assignee, now);

        // Append recurring reminding tasks to regular reminding tasks
        regularTasks.addAll(recurringTask);
        return regularTasks;
    }

    /*
     * Get user's tasks between the request start time and request end time.
     *
     * @param assignee
     * @param startTime
     * @param endTime
     * @retVal List<com.bulletjournal.controller.models.Task> - A list of tasks
     */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public List<Task> getTasksBetween(String assignee, ZonedDateTime startTime, ZonedDateTime endTime) {

        List<Task> tasks = this.taskRepository.findTasksOfAssigneeBetween(
                assignee, Timestamp.from(startTime.toInstant()), Timestamp.from(endTime.toInstant()));

        List<Task> recurrentTasks = this.getRecurringTasks(assignee, startTime, endTime);

        tasks.addAll(recurrentTasks);
        return tasks;
    }

    /*
     * Get recurring reminding tasks from database.
     *
     * Reminding tasks qualifications:
     * 1. Reminding Time is before current time.
     * 2. Starting time is after the current time.
     *
     * @param assignee
     * @param now
     * @retVal List<com.bulletjournal.controller.models.Task> - A list of tasks
     */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public List<com.bulletjournal.controller.models.Task> getRecurringTaskNeedReminding(String assignee, ZonedDateTime now) {
        ZonedDateTime maxRemindingTime = now.plusHours(ZonedDateTimeHelper.MAX_HOURS_BEFORE);
        return this.getRecurringTasks(assignee, now, maxRemindingTime)
                .stream()
                .filter(t -> t.getReminderDateTime().before(ZonedDateTimeHelper.getTimestamp(now))
                        && t.getStartTime().after(ZonedDateTimeHelper.getTimestamp(now)))
                .map(TaskModel::toPresentationModel)
                .collect(Collectors.toList());
    }

    /*
     * Get all recurrent tasks of an assignee within requested start time and end time
     *
     * Procedure:
     * 1. Fetch all tasks with recurrence rule
     * 2. Obtain new DateTime instance by using RecurrenceRule iterator
     * 3. Clone the original recurring task and set its start/end time and reminding setting
     *
     * @param assignee
     * @param startTime
     * @param endTime
     * @retVal List<Task> - A list of recurrent tasks within the time range
     */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public List<Task> getRecurringTasks(String assignee, ZonedDateTime startTime, ZonedDateTime endTime) {
        List<Task> recurringTasksBetween = new ArrayList<>();
        List<Task> recurrentTasks = this.taskRepository.findTasksByAssignedToAndRecurrenceRuleNotNull(assignee);
        DateTime startDateTime = ZonedDateTimeHelper.getDateTime(startTime);
        DateTime endDateTime = ZonedDateTimeHelper.getDateTime(endTime);

        for (Task t : recurrentTasks) {
            String recurrenceRule = t.getRecurrenceRule();
            String timezone = t.getTimezone();
            try {
                BuJoRecurrenceRule rule = new BuJoRecurrenceRule(recurrenceRule, timezone);

                RecurrenceRuleIterator it = rule.getIterator();
                while (it.hasNext()) {
                    DateTime currDateTime = it.nextDateTime();
                    if (currDateTime.after(endDateTime)) {
                        break;
                    }
                    if (currDateTime.before(startDateTime)) {
                        continue;
                    }
                    Task cloned = (Task) t.clone();

                    String date = ZonedDateTimeHelper.getDate(currDateTime);
                    String time = ZonedDateTimeHelper.getTime(currDateTime);

                    cloned.setDueDate(date); // Set due date
                    cloned.setDueTime(time); // Set due time

                    // Set start time and end time
                    cloned.setStartTime(Timestamp.from(ZonedDateTimeHelper.getStartTime(date, time, timezone).toInstant()));
                    cloned.setEndTime(Timestamp.from(ZonedDateTimeHelper.getEndTime(date, time, timezone).toInstant()));

                    cloned.setReminderSetting(t.getReminderSetting()); // Set reminding setting to cloned
                    recurringTasksBetween.add(cloned);
                }
            } catch (InvalidRecurrenceRuleException e) {
                throw new IllegalArgumentException("Recurrence rule format invalid");
            } catch (CloneNotSupportedException e) {
                throw new IllegalStateException("Clone new Task failed");
            }
        }
        return recurringTasksBetween;
    }

    /*
     * Create task based on CreateTaskParams
     *
     * @param projectId
     * @param owner
     * @param createTaskParams
     * @retVal Task - A repository task model
     */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public Task create(Long projectId, String owner, CreateTaskParams createTaskParams) {

        Project project = this.projectDaoJpa.getProject(projectId, owner);
        if (!ProjectType.TODO.equals(ProjectType.getType(project.getType()))) {
            throw new BadRequestException("Project Type expected to be TODO while request is " + project.getType());
        }

        Task task = new Task();
        task.setProject(project);
        task.setAssignedTo(owner);
        task.setDueDate(createTaskParams.getDueDate());
        task.setDueTime(createTaskParams.getDueTime());
        task.setOwner(owner);
        task.setName(createTaskParams.getName());
        task.setTimezone(createTaskParams.getTimezone());
        task.setDuration(createTaskParams.getDuration());
        task.setAssignedTo(createTaskParams.getAssignedTo());
        task.setRecurrenceRule(createTaskParams.getRecurrenceRule());

        String date = createTaskParams.getDueDate();
        String time = createTaskParams.getDueTime();
        String timezone = createTaskParams.getTimezone();

        if (date != null) {
            task.setStartTime(Timestamp.from(ZonedDateTimeHelper.getStartTime(date, time, timezone).toInstant()));
            task.setEndTime(Timestamp.from(ZonedDateTimeHelper.getEndTime(date, time, timezone).toInstant()));
        }

        ReminderSetting reminderSetting = createTaskParams.getReminderSetting();

        if (reminderSetting == null) {
            reminderSetting = new ReminderSetting(null, null,
                    this.userDaoJpa.getByName(owner).getReminderBeforeTask().getValue());
        }
        task.setReminderSetting(reminderSetting);

        task = this.taskRepository.save(task);

        final ProjectTasks projectTasks = this.projectTasksRepository.findById(projectId).orElseGet(ProjectTasks::new);

        String newRelations = HierarchyProcessor.addItem(projectTasks.getTasks(), task.getId());
        projectTasks.setProjectId(projectId);
        projectTasks.setTasks(newRelations);
        this.projectTasksRepository.save(projectTasks);
        return task;
    }

    /*
     * Partially update task based on UpdateTaskParams
     *
     * @param requester
     * @param taskId
     * @param updateTaskParams
     * @retVal List<Event> - a list of events for users notification
     */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public Task partialUpdate(String requester, Long taskId, UpdateTaskParams updateTaskParams, List<Event> events) {

        Task task = this.getProjectItem(taskId, requester);

        this.authorizationService.checkAuthorizedToOperateOnContent(
                task.getOwner(), requester, ContentType.TASK, Operation.UPDATE,
                taskId, task.getProject().getOwner());

        DaoHelper.updateIfPresent(
                updateTaskParams.hasName(), updateTaskParams.getName(), task::setName);

        updateAssignee(requester, taskId, updateTaskParams, task, events);

        String date = updateTaskParams.getDueDate();
        String time = updateTaskParams.getDueTime();
        String timezone = updateTaskParams.getTimezone();

        task.setDueDate(date);
        task.setDueTime(time);
        task.setTimezone(timezone);
        task.setRecurrenceRule(updateTaskParams.getRecurrenceRule());
        task.setDuration(updateTaskParams.getDuration());

        if (updateTaskParams.hasDueDate()) {
            task.setStartTime(Timestamp.from(ZonedDateTimeHelper.getStartTime(date, time, timezone).toInstant()));
            task.setEndTime(Timestamp.from(ZonedDateTimeHelper.getEndTime(date, time, timezone).toInstant()));
        } else {
            task.setStartTime(null);
            task.setEndTime(null);
            if (!updateTaskParams.hasRecurrenceRule()) {
                //  set no reminder
                updateTaskParams.setReminderSetting(new ReminderSetting());
            }
        }

        DaoHelper.updateIfPresent(updateTaskParams.hasReminderSetting(), updateTaskParams.getReminderSetting(),
                task::setReminderSetting);

        return this.taskRepository.save(task);
    }

    /*
     * Add assignee change event to notification
     *
     * @param requester
     * @param taskId
     * @param updateTaskParams
     * @param task
     * @retVal List<Event> - a list of events for users notification
     */
    private List<Event> updateAssignee(String requester, Long taskId, UpdateTaskParams updateTaskParams,
                                       Task task, List<Event> events) {
        String newAssignee = updateTaskParams.getAssignedTo();
        String oldAssignee = task.getAssignedTo();
        if (newAssignee != null && !Objects.equals(newAssignee, oldAssignee)) {
            task.setAssignedTo(newAssignee);
            if (!Objects.equals(newAssignee, requester)) {
                events.add(new Event(newAssignee, taskId, task.getName()));
            }
            if (!Objects.equals(oldAssignee, requester)) {
                events.add(new Event(oldAssignee, taskId, task.getName()));
            }
        }
        return events;
    }

    /*
     * Set a task to complete
     *
     * 1. Get task from task table
     * 2. Delete task and its sub tasks from task table
     * 3. Add task and its sub tasks to complete task table
     *
     * @param requester
     * @param taskId
     * @retVal CompleteTask - a repository model complete task object
     */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public CompletedTask complete(String requester, Long taskId) {

        Task task = this.getProjectItem(taskId, requester);

        this.authorizationService.checkAuthorizedToOperateOnContent(task.getOwner(),
                requester, ContentType.TASK,
                Operation.UPDATE, task.getProject().getId(), task.getProject().getOwner());


        deleteTaskAndAdjustRelations(
                requester, task,
                (targetTasks) -> {
                    targetTasks.forEach(t -> {
                        if (!t.getId().equals(task.getId())) {
                            this.completedTaskRepository.save(new CompletedTask(t));
                        }
                    });
                    this.taskRepository.deleteAll(targetTasks);
                },
                (target) -> {
                });

        CompletedTask completedTask = new CompletedTask(task);
        this.completedTaskRepository.save(completedTask);
        return completedTask;
    }

    /*
     * Update sub tasks relation
     *
     * @param projectId
     * @param tasks - a list of tasks
     * @retVal void
     */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public void updateUserTasks(Long projectId, List<com.bulletjournal.controller.models.Task> tasks) {
        Optional<ProjectTasks> projectTasksOptional = this.projectTasksRepository.findById(projectId);
        final ProjectTasks projectTasks = projectTasksOptional.orElseGet(ProjectTasks::new);

        projectTasks.setTasks(TaskRelationsProcessor.processRelations(tasks));
        projectTasks.setProjectId(projectId);

        this.projectTasksRepository.save(projectTasks);
    }

    /*
     * Delete requester's task by task identifier
     *
     * @param requester
     * @param taskId
     * @retVal List<Event> - a list of notification events
     */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public List<Event> deleteTask(String requester, Long taskId) {
        Task task = this.getProjectItem(taskId, requester);
        Project project = deleteTaskAndAdjustRelations(
                requester, task,
                (targetTasks) -> this.taskRepository.deleteAll(targetTasks),
                (target) -> {
                });

        return generateEvents(task, requester, project);
    }

    /*
     * Delete task and adjust project relations after task completion
     *
     *
     * @param requester
     * @param task
     * @param targetTasksOperator - Consumer class or Lambda function operate upon target tasks list
     * @param targetOperator - Consumer class or Lambda function operates upon target HierarchyItem
     * @retVal Project
     */
    private Project deleteTaskAndAdjustRelations(
            String requester, Task task,
            Consumer<List<Task>> targetTasksOperator,
            Consumer<HierarchyItem> targetOperator) {
        Project project = task.getProject();
        Long projectId = project.getId();
        this.authorizationService.checkAuthorizedToOperateOnContent(task.getOwner(), requester, ContentType.TASK,
                Operation.DELETE, projectId, project.getOwner());

        ProjectTasks projectTasks = this.projectTasksRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("ProjectTasks by " + projectId + " not found"));

        String relations = projectTasks.getTasks();

        // delete tasks and its subTasks
        List<Task> targetTasks = this.taskRepository.findAllById(
                HierarchyProcessor.getSubItems(relations, task.getId()));
        targetTasksOperator.accept(targetTasks);

        // Update task relations
        HierarchyItem[] target = new HierarchyItem[1];
        List<HierarchyItem> hierarchy = HierarchyProcessor.removeTargetItem(relations, task.getId(), target);
        targetOperator.accept(target[0]);

        projectTasks.setTasks(GSON.toJson(hierarchy));
        this.projectTasksRepository.save(projectTasks);

        return project;
    }

    /*
     * Delete completed tasks from database
     *
     * 1. Check if the requester is authorized for the operation
     * 2. Remove task from complete tasks table
     *
     * @param requester
     * @param taskId
     * @retVal - A list of notification events
     */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public List<Event> deleteCompletedTask(String requester, Long taskId) {
        CompletedTask task = this.completedTaskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task " + taskId + " not found"));
        Project project = task.getProject();
        this.authorizationService.checkAuthorizedToOperateOnContent(task.getOwner(),
                requester, ContentType.TASK,
                Operation.DELETE, project.getId(), task.getProject().getOwner());
        this.completedTaskRepository.delete(task);
        return generateEvents(task, requester, project);
    }

    /*
     * Generate events for notification
     *
     * @param task
     * @param requester
     * @param project
     * @retVal List<Event> - a list of notification events
     */
    private List<Event> generateEvents(TaskModel task, String requester, Project project) {
        List<Event> events = new ArrayList<>();
        for (UserGroup userGroup : project.getGroup().getUsers()) {
            if (!userGroup.isAccepted()) {
                continue;
            }
            // skip send event to self
            String username = userGroup.getUser().getName();
            if (userGroup.getUser().getName().equals(requester)) {
                continue;
            }
            events.add(new Event(username, task.getId(), task.getName()));
        }
        return events;
    }

    /*
     * Get completed tasks by project from database
     *
     * @param projectId
     * @param requester
     * @retVal - A list of tasks
     */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public List<CompletedTask> getCompletedTasks(Long projectId, String requester) {
        Project project = this.projectDaoJpa.getProject(projectId, requester);
        List<CompletedTask> completedTasks = this.completedTaskRepository.findCompletedTaskByProject(project);
        return completedTasks
                .stream().sorted((c1, c2) -> c2.getUpdatedAt().compareTo(c1.getUpdatedAt()))
                .collect(Collectors.toList());
    }

    /*
     * Uncomplete completed task.
     *
     * 1. Check if requester is allowed to operate with this action
     * 2. Remove task from Completed Task table
     * 3. Create a new task and add it to regular Task table
     *
     * @param requester
     * @param taskId
     * @retVal Long?
     */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public Long uncomplete(String requester, Long taskId) {
        CompletedTask task = this.completedTaskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task " + taskId + " not found"));
        Long projectId = task.getProject().getId();
        this.authorizationService.checkAuthorizedToOperateOnContent(task.getOwner(),
                requester, ContentType.TASK,
                Operation.UPDATE, projectId, task.getProject().getOwner());
        this.completedTaskRepository.delete(task);
        return create(projectId, task.getOwner(), getCreateTaskParams(task)).getId();
    }

    /*
     * Remove reminder setting from CreateTaskParams
     *
     * @param task
     * @retVal CreateTaskParams
     */
    private CreateTaskParams getCreateTaskParams(CompletedTask task) {
        return new CreateTaskParams(task.getName(), task.getAssignedTo(), task.getDueDate(),
                task.getDueTime(), task.getDuration(), null, task.getTimezone(), task.getRecurrenceRule());
    }

    /*
     * Move task from one project to another
     *
     * @requester
     * @taskId
     * @targetProject
     * @retVal void
     */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public void move(String requester, Long taskId, Long targetProject) {
        final Project project = this.projectDaoJpa.getProject(targetProject, requester);

        Task task = this.getProjectItem(taskId, requester);

        if (!Objects.equals(task.getProject().getType(), project.getType())) {
            throw new BadRequestException("Cannot move to Project Type " + project.getType());
        }

        this.authorizationService.checkAuthorizedToOperateOnContent(task.getOwner(), requester, ContentType.TASK,
                Operation.UPDATE, project.getId(), project.getOwner());

        deleteTaskAndAdjustRelations(
                requester, task,
                (targetTasks) -> targetTasks.forEach((t) -> {
                    t.setProject(project);
                    this.taskRepository.save(t);
                }),
                (target) -> {
                    final ProjectTasks projectTasks = this.projectTasksRepository.findById(targetProject)
                            .orElseGet(ProjectTasks::new);
                    String newRelations = HierarchyProcessor.addItem(projectTasks.getTasks(), target);
                    projectTasks.setTasks(newRelations);
                    projectTasks.setProjectId(targetProject);
                    this.projectTasksRepository.save(projectTasks);
                });
    }

    /*
     * Get Content Jpa Repository
     *
     * @retVal JpaRepository
     */
    @Override
    public JpaRepository getContentJpaRepository() {
        return this.taskContentRepository;
    }

    /*
     * Get Contents for project
     *
     * @param projectItemId
     * @param requester
     * @retVal List<TaskContent>
     */
    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public List<TaskContent> getContents(Long projectItemId, String requester) {
        Task task = this.getProjectItem(projectItemId, requester);
        List<TaskContent> contents = this.taskContentRepository.findTaskContentByTask(task)
                .stream().sorted(Comparator.comparingLong(a -> a.getCreatedAt().getTime()))
                .collect(Collectors.toList());
        return contents;
    }
}
